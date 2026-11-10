package com.zhaojin.flashbuy.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhaojin.flashbuy.entity.Merchant;
import com.zhaojin.flashbuy.utils.CacheClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.zhaojin.flashbuy.utils.RedisConstants.CACHE_MERCHANT_KEY;
import static com.zhaojin.flashbuy.utils.RedisConstants.CACHE_MERCHANT_TTL;

/**
 * 商户详情两级缓存：Caffeine(L1) -> Redis(L2) -> MySQL。
 *
 * L1 只保存很短时间，Redis 作为跨实例共享的 L2；
 * Optional 用来缓存“不存在”，因为 Caffeine 不允许 null 值。
 */
@Service
public class MerchantCacheService {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${cache.merchant.local-max-size:10000}")
    private long localMaxSize;

    @Value("${cache.merchant.local-ttl-seconds:120}")
    private long localTtlSeconds;

    private Cache<Long, Optional<Merchant>> localCache;

    @PostConstruct
    public void init() {
        localCache = Caffeine.newBuilder()
                .maximumSize(localMaxSize)
                .expireAfterWrite(Duration.ofSeconds(localTtlSeconds))
                .recordStats()
                .build();
    }

    /**
     * 读不到就加载：L1 未命中时执行 loader，loader 内部继续读 Redis、MySQL 并回填。
     * 同一个 key 的并发加载由 Caffeine 合并，避免单实例内缓存击穿。
     */
    public Merchant get(Long id, Function<Long, Merchant> dbFallback) {
        Optional<Merchant> cached = localCache.get(id, key -> Optional.ofNullable(
                cacheClient.queryWithPassThrough(CACHE_MERCHANT_KEY, key, Merchant.class, dbFallback,
                        CACHE_MERCHANT_TTL, TimeUnit.MINUTES)
        ));
        return cached.orElse(null);
    }

    /** 商户更新后同时失效本地 L1 与 Redis L2。 */
    public void evict(Long id) {
        localCache.invalidate(id);
        stringRedisTemplate.delete(CACHE_MERCHANT_KEY + id);
    }
}
