package com.flashbuy.service.impl;

import cn.hutool.json.JSONUtil;
import com.flashbuy.dto.Result;
import com.flashbuy.entity.MerchantType;
import com.flashbuy.mapper.MerchantTypeMapper;
import com.flashbuy.service.IMerchantTypeService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
public class MerchantTypeServiceImpl extends ServiceImpl<MerchantTypeMapper, MerchantType> implements IMerchantTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = CACHE_MERCHANTTYPE_KEY;
        List<String> typeList=stringRedisTemplate.opsForList().range(key, 0, -1);
        if (typeList!=null&&!typeList.isEmpty()){
            List<MerchantType> merchantTypes = typeList.stream().map(merchantType -> JSONUtil.toBean(merchantType, MerchantType.class)).toList();
            return Result.ok(merchantTypes);
        }

        List<MerchantType> merchantTypeList  = query().orderByAsc("sort").list();
        if (merchantTypeList==null || merchantTypeList.isEmpty())
            return Result.fail("店铺类型不存在");
        List<String> jsonTypeList = merchantTypeList.stream().map(merchantType -> JSONUtil.toJsonStr(merchantType)).toList();
        stringRedisTemplate.opsForList().rightPushAll(key, jsonTypeList);
        stringRedisTemplate.expire(key, CACHE_MERCHANTTYPE_TTL, TimeUnit.MINUTES);

        return Result.ok(merchantTypeList);
    }
}
