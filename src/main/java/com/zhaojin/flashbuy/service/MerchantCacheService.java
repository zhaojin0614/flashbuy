package com.zhaojin.flashbuy.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.zhaojin.flashbuy.entity.Merchant;
import com.zhaojin.flashbuy.utils.CacheClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.zhaojin.flashbuy.utils.RedisConstants.CACHE_MERCHANT_KEY;
import static com.zhaojin.flashbuy.utils.RedisConstants.CACHE_MERCHANT_LIST_KEY;
import static com.zhaojin.flashbuy.utils.RedisConstants.CACHE_MERCHANT_LIST_TTL;
import static com.zhaojin.flashbuy.utils.RedisConstants.CACHE_MERCHANT_TTL;

/**
 * 商户两级缓存：Caffeine(L1) -> Redis(L2) -> MySQL。
 *
 * 详情与列表各有一份 L1；Optional 用于缓存“不存在”，因为 Caffeine 不允许 null。
 * 列表现阶段只缓存评分/人气排序，附近距离依赖用户坐标，不做页面缓存。
 */
@Service
public class MerchantCacheService {

    /** 跨实例失效频道：消息格式 id:typeId，typeId 可为空。 */
    public static final String CACHE_INVALIDATE_CHANNEL = "cache:invalidate:merchant";

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${cache.merchant.enabled:true}")
    private boolean cacheEnabled;

    @Value("${cache.merchant.local-max-size:10000}")
    private long localMaxSize;

    @Value("${cache.merchant.local-ttl-seconds:120}")
    private long localTtlSeconds;

    private Cache<Long, Optional<Merchant>> localCache;
    private Cache<String, List<Merchant>> listCache;

    /** 本机热点商户访问计数，仅用于统计接口，不参与缓存正确性。 */
    private final ConcurrentHashMap<Long, LongAdder> detailAccess = new ConcurrentHashMap<>();

    /** 延迟双删线程：更新后 1 秒再删一次，覆盖并发读回填的旧值。 */
    private final ScheduledExecutorService evictExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "merchant-cache-evict");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void init() {
        localCache = Caffeine.newBuilder()
                .maximumSize(localMaxSize)
                .expireAfterWrite(Duration.ofSeconds(localTtlSeconds))
                .recordStats()
                .build();
        listCache = Caffeine.newBuilder()
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
        if (!cacheEnabled) {
            return dbFallback.apply(id);
        }
        detailAccess.computeIfAbsent(id, key -> new LongAdder()).increment();
        Optional<Merchant> cached = localCache.get(id, key -> Optional.ofNullable(
                cacheClient.queryWithPassThrough(CACHE_MERCHANT_KEY, key, Merchant.class, dbFallback,
                        CACHE_MERCHANT_TTL, TimeUnit.MINUTES)
        ));
        return cached.orElse(null);
    }

    /**
     * 列表读取：L1 -> Redis -> DB；loader 只负责查 DB。
     * 空列表是合法值，直接缓存 JSON 数组。
     */
    public List<Merchant> getList(String key, Supplier<List<Merchant>> loader) {
        if (!cacheEnabled) {
            List<Merchant> list = loader.get();
            return list == null ? Collections.emptyList() : list;
        }
        return listCache.get(key, k -> {
            String json = stringRedisTemplate.opsForValue().get(k);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toList(json, Merchant.class);
            }
            List<Merchant> list = loader.get();
            if (list == null) {
                list = Collections.emptyList();
            }
            stringRedisTemplate.opsForValue().set(k, JSONUtil.toJsonStr(list),
                    CACHE_MERCHANT_LIST_TTL, TimeUnit.MINUTES);
            return list;
        });
    }

    /**
     * 商户新增/更新后失效详情与分类列表，并广播给其他实例。
     * 立即删一次，1 秒后再删一次，覆盖并发读回填的旧值。
     */
    public void evictMerchant(Long id, Long typeId) {
        evictNow(id, typeId);
        publishInvalidation(id, typeId);
        evictExecutor.schedule(() -> {
            evictNow(id, typeId);
            publishInvalidation(id, typeId);
        }, 1, TimeUnit.SECONDS);
    }

    private void evictNow(Long id, Long typeId) {
        if (id != null) {
            localCache.invalidate(id);
            stringRedisTemplate.delete(CACHE_MERCHANT_KEY + id);
        }
        if (typeId != null) {
            evictListByType(typeId);
        }
    }

    private void publishInvalidation(Long id, Long typeId) {
        if (id == null) {
            return;
        }
        stringRedisTemplate.convertAndSend(CACHE_INVALIDATE_CHANNEL,
                id + ":" + (typeId == null ? "" : typeId));
    }

    /** Pub/Sub 订阅端调用：只失效本机 L1，Redis 已由发布方删除。 */
    public void invalidateLocal(Long id, Long typeId) {
        if (id != null) {
            localCache.invalidate(id);
        }
        if (typeId != null) {
            evictListLocal(typeId);
        }
    }

    /** 商户新增/更新后失效该分类的列表缓存（本机 L1 + Redis L2）。 */
    public void evictListByType(Long typeId) {
        if (typeId == null) {
            return;
        }
        evictListLocal(typeId);
        // 用 Redis KEYS 模式匹配，找出该分类所有列表 key
        Set<String> keys = stringRedisTemplate.keys(CACHE_MERCHANT_LIST_KEY + typeId + ":*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private void evictListLocal(Long typeId) {
        String prefix = CACHE_MERCHANT_LIST_KEY + typeId + ":";
        List<String> keys = listCache.asMap().keySet().stream() // ① 拿到缓存里所有 key 的实时视图
                .filter(key -> key.startsWith(prefix))    // ② 筛出属于该分类的
                .collect(Collectors.toList());
        if (!keys.isEmpty()) {
            listCache.invalidateAll(keys);                      // ③ 批量失效
        }
    }

    @PreDestroy
    public void shutdown() {
        evictExecutor.shutdownNow();
    }

    /** 缓存统计与热点商户（累计访问次数 Top5），用于演示与排查。 */
    public Map<String, Object> stats() {
        CacheStats detailStats = localCache.stats();
        CacheStats listStats = listCache.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", cacheEnabled);
        result.put("detail", Map.of(
                "size", localCache.estimatedSize(),
                "hitRate", detailStats.hitRate(),
                "hitCount", detailStats.hitCount(),
                "missCount", detailStats.missCount(),
                "evictionCount", detailStats.evictionCount()));
        result.put("list", Map.of(
                "size", listCache.estimatedSize(),
                "hitRate", listStats.hitRate(),
                "hitCount", listStats.hitCount(),
                "missCount", listStats.missCount(),
                "evictionCount", listStats.evictionCount()));
        result.put("hotMerchants", detailAccess.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(5)
                .map(entry -> Map.of("id", entry.getKey(), "access", entry.getValue().sum()))
                .collect(Collectors.toList()));
        return result;
    }
}
