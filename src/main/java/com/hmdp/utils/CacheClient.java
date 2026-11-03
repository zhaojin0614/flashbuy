package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //逻辑过期写入
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透
    public<R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                        Long time, TimeUnit unit){
        String key=keyPrefix + id;
        //1.从redis查询缓存
        String json=stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            //2.存在，返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值（解决缓存穿透，即虽然命中了，但不存在的key其value设置的为空）
        if (json!=null){
            //返回错误信息
            return null;
        }

        R r = dbFallback.apply(id);
        if (r==null){
            //3.数据库不存在
            // 将空值写入redis（解决缓存穿透）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误
            return null;
        }

        //4.存在，写入redis
        this.set(key, r, time, unit);

        //5.返回
        return r;
    }

    // 逻辑过期
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    public<R,ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                           Long time, TimeUnit unit) {
        String key=keyPrefix + id;
        //1.从redis查询缓存
        String json=stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)){
            //2.存在，返回
            return null;
        }
        // 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data=(JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime=redisData.getExpireTime();

        // 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺信息
            return r;
        }
        // 已过期，需要缓存重建
        String lockKey=lockKeyPrefix + id;
        // 获取互斥锁
        boolean isLock=tryLock(lockKey);
        // 判断是否成功
        if(isLock){
            // 成功
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1= dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }

            });
        }
        return r;
    }

    // 互斥锁解决缓存击穿
    public<R,ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                  Long time, TimeUnit unit){
        String key=keyPrefix + id;
        //1.从redis查询缓存
        String json=stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            //2.存在，返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json!=null){
            return null;
        }

        String lockKey=LOCK_SHOP_KEY + id;
        R r=null;
        try {
            //3.获取互斥锁
            boolean isLock=tryLock(lockKey);
            if(!isLock){
                //4.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            //5.获取锁成功，查询数据库
            r=dbFallback.apply(id);
            if (r==null){
                //6.数据库不存在，写入空值（解决缓存穿透）
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //7.存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //8.释放锁
            unlock(lockKey);
        }
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
