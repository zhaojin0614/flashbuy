package com.zhaojin.flashbuy;

import com.zhaojin.flashbuy.entity.Merchant;
import com.zhaojin.flashbuy.service.impl.MerchantServiceImpl;
import com.zhaojin.flashbuy.utils.CacheClient;
import com.zhaojin.flashbuy.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.zhaojin.flashbuy.utils.RedisConstants.CACHE_MERCHANT_KEY;
import static com.zhaojin.flashbuy.utils.RedisConstants.MERCHANT_GEO_KEY;

@SpringBootTest
class FlashbuyApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private MerchantServiceImpl merchantService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testSaveMerchant() {
        Merchant merchant = merchantService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_MERCHANT_KEY + 1L, merchant, 10L, TimeUnit.SECONDS);
    }

    @Test
    void loadMerchantData() {
        // 1.查询商户信息
        List<Merchant> list = merchantService.list();
        // 2.把商户分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Merchant>> map = list.stream().collect(Collectors.groupingBy(Merchant::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Merchant>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = MERCHANT_GEO_KEY + typeId;
            // 3.2.获取同类型的商户的集合
            List<Merchant> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Merchant merchant : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        merchant.getId().toString(),
                        new Point(merchant.getX(), merchant.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }
}
