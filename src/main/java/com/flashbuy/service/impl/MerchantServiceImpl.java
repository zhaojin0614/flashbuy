package com.flashbuy.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashbuy.dto.Result;
import com.flashbuy.entity.Merchant;
import com.flashbuy.mapper.MerchantMapper;
import com.flashbuy.service.IMerchantService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.flashbuy.utils.CacheClient;
import com.flashbuy.utils.RedisData;
import com.flashbuy.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.flashbuy.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class MerchantServiceImpl extends ServiceImpl<MerchantMapper, Merchant> implements IMerchantService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        Merchant merchant = cacheClient.queryWithPassThrough(CACHE_MERCHANT_KEY, id, Merchant.class, this::getById,
                CACHE_MERCHANT_TTL, TimeUnit.MINUTES);

        //缓存击穿-逻辑过期
//        Merchant merchant = cacheClient.queryWithLogicalExpire(CACHE_MERCHANT_KEY,LOCK_MERCHANT_KEY, id, Merchant.class, this::getById,
//                CACHE_MERCHANT_TTL, TimeUnit.MINUTES);

        // 缓存击穿问题
        // 互斥锁方案
//        return queryWithMutex(id);

        if (merchant == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(merchant);
    }

    // 互斥锁
//    private Result queryWithMutex(Long id) {
//        String key=CACHE_MERCHANT_KEY + id;
//        //1.从redis查询缓存
//        String merchantJson=stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(merchantJson)){
//            //2.存在，返回
//            return Result.ok(JSONUtil.toBean(merchantJson, Merchant.class));
//        }
//        //判断命中的是否是空值（解决缓存穿透，即虽然命中了，但不存在的key其value设置的为空）
//        if (merchantJson!=null){
//            //返回错误信息
//            return Result.fail("店铺信息不存在！");
//        }
//
//        // 解决缓存击穿
//        // 获取互斥锁
//        String lockKey="lock:merchant:" + id;
//
//        Merchant merchant= null;
//        try {
//            boolean isLock=tryLock(lockKey);
//            // 判断是否成功
//            if(!isLock){
//                // 失败，休眠并充实
//                Thread.sleep(50);
//                return queryById(id);
//            }
//            //成功，根据id查询数据库
//            //redis不存在，查询数据库
//            merchant = getById(id);
//            if (merchant==null){
//                //3.数据库不存在
//                // 将空值写入redis（解决缓存穿透）
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                // 返回错误
//                return Result.fail("店铺不存在!");
//            }
//
//            //4.存在，写入redis
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(merchant), CACHE_MERCHANT_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 释放互斥锁
//            unlock(lockKey);
//        }
//
//        //5.返回
//        return Result.ok(merchant);
//    }




    @Transactional
    @Override
    public Result update(Merchant merchant) {
        Long id = merchant.getId();
        if (id==null)
            return Result.fail("店铺id不能为空");
        //1.更新数据库
        updateById(merchant);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_MERCHANT_KEY + merchant.getId());
        return Result.ok();
    }

    @Override
    public Result queryMerchantByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Merchant> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：merchantId、distance
        String key = MERCHANT_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String merchantIdStr = result.getContent().getName();
            ids.add(Long.valueOf(merchantIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(merchantIdStr, distance);
        });
        // 5.根据id查询Merchant
        String idStr = StrUtil.join(",", ids);
        List<Merchant> merchants = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Merchant merchant : merchants) {
            merchant.setDistance(distanceMap.get(merchant.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(merchants);
    }
}
