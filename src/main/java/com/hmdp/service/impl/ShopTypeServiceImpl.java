package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOPTYPE_KEY;
        List<String> typeList=stringRedisTemplate.opsForList().range(key, 0, -1);
        if (typeList!=null&&!typeList.isEmpty()){
            List<ShopType> shopTypes = typeList.stream().map(shopType -> JSONUtil.toBean(shopType, ShopType.class)).toList();
            return Result.ok(shopTypes);
        }

        List<ShopType> shopTypeList  = query().orderByAsc("sort").list();
        if (shopTypeList==null || shopTypeList.isEmpty())
            return Result.fail("店铺类型不存在");
        List<String> jsonTypeList = shopTypeList.stream().map(shopType -> JSONUtil.toJsonStr(shopType)).toList();
        stringRedisTemplate.opsForList().rightPushAll(key, jsonTypeList);
        stringRedisTemplate.expire(key, CACHE_SHOPTYPE_TTL, TimeUnit.MINUTES);

        return Result.ok(shopTypeList);
    }
}
