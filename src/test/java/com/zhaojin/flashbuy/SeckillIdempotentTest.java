package com.zhaojin.flashbuy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhaojin.flashbuy.dto.SeckillMessage;
import com.zhaojin.flashbuy.entity.Order;
import com.zhaojin.flashbuy.entity.Product;
import com.zhaojin.flashbuy.entity.ProductRelease;
import com.zhaojin.flashbuy.mapper.OrderMapper;
import com.zhaojin.flashbuy.service.IProductReleaseService;
import com.zhaojin.flashbuy.service.IProductService;
import jakarta.annotation.Resource;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static com.zhaojin.flashbuy.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消费幂等集成测试（任务二，需本地 MySQL/Redis/RocketMQ 环境）：
 * 向真实 topic 连续投递两条相同 orderId 的消息，断言只落一单——
 * 第二条应被 SETNX 防重层或 DB count 校验拦截，而不是产生重复订单。
 */
@SpringBootTest
class SeckillIdempotentTest {

    private static final long PRODUCT_ID = 99002L;
    private static final long USER_ID = 99777L;
    private static final long ORDER_ID = 99002000001L;

    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private IProductService productService;
    @Resource
    private IProductReleaseService productReleaseService;
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setMerchantId(1L);
        product.setTitle("idempotent-test-product");
        product.setPayValue(100L);
        product.setActualValue(200L);
        product.setType(1);
        product.setStatus(1);
        productService.save(product);

        ProductRelease release = new ProductRelease();
        release.setProductId(PRODUCT_ID);
        release.setStock(1);
        release.setBeginTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        release.setEndTime(LocalDateTime.of(2027, 12, 31, 23, 59));
        productReleaseService.save(release);

        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + PRODUCT_ID, "1");
    }

    @AfterEach
    void tearDown() {
        orderMapper.delete(new QueryWrapper<Order>().eq("product_id", PRODUCT_ID).eq("user_id", USER_ID));
        productReleaseService.removeById(PRODUCT_ID);
        productService.removeById(PRODUCT_ID);
        stringRedisTemplate.delete(java.util.List.of(
                SECKILL_STOCK_KEY + PRODUCT_ID,
                SECKILL_ORDER_KEY + PRODUCT_ID,
                SECKILL_CONSUMING_KEY + ORDER_ID));
    }

    @Test
    void duplicateDeliveryCreatesOnlyOneOrder() throws Exception {
        SeckillMessage message = new SeckillMessage(USER_ID, PRODUCT_ID, ORDER_ID);
        // 1.同一 orderId 连发两条（模拟 MQ 重试/网络重发导致的重复投递）
        rocketMQTemplate.syncSend(SeckillMessage.TOPIC, message);
        rocketMQTemplate.syncSend(SeckillMessage.TOPIC, message);

        // 2.轮询等待第一条消息异步落库（最多 15s）
        long deadline = System.currentTimeMillis() + 15_000;
        Long count;
        do {
            Thread.sleep(500);
            count = orderMapper.selectCount(new QueryWrapper<Order>()
                    .eq("id", ORDER_ID).eq("user_id", USER_ID).eq("product_id", PRODUCT_ID));
        } while ((count == null || count == 0) && System.currentTimeMillis() < deadline);
        assertTrue(count != null && count == 1, "第一条消息应在 15s 内落库");

        // 3.再给第二条消息留出消费窗口，然后断言没有产生第二个订单
        Thread.sleep(3_000);
        Long finalCount = orderMapper.selectCount(new QueryWrapper<Order>()
                .eq("product_id", PRODUCT_ID).eq("user_id", USER_ID));
        assertEquals(1L, finalCount, "重复投递必须被幂等层拦截，订单只能有一行");

        // 4.DB 库存只扣一次（Redis 库存由生产端 Lua 预扣，不经消费链路，此处只验 DB）
        ProductRelease after = productReleaseService.getById(PRODUCT_ID);
        assertEquals(0, after.getStock(), "重复消费不得再次扣减 DB 库存");
    }
}
