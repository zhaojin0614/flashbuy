package com.zhaojin.flashbuy.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.Order;
import com.zhaojin.flashbuy.entity.Merchant;
import com.zhaojin.flashbuy.entity.Product;
import com.zhaojin.flashbuy.mapper.OrderMapper;
import com.zhaojin.flashbuy.service.IMerchantService;
import com.zhaojin.flashbuy.service.IProductReleaseService;
import com.zhaojin.flashbuy.service.IProductService;
import com.zhaojin.flashbuy.service.IOrderService;
import com.zhaojin.flashbuy.utils.RedisConstants;
import com.zhaojin.flashbuy.utils.RedisIdWorker;
import com.zhaojin.flashbuy.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Slf4j
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    @Resource
    private IProductReleaseService seckillProductService;

    @Resource
    private IProductService productService;

    @Resource
    private IMerchantService merchantService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 自注入代理：消费线程不是代理调用上下文，AopContext.currentProxy() 在这里拿不到代理，
     * 只能靠自注入拿到 IOrderService 的代理对象，@Transactional 才能生效。
     * @Lazy 延迟到首次调用时再解析，避免 Bean 自我注入的初始化顺序问题
     */
    @Lazy
    @Resource
    private IOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private volatile boolean running = true;

    @PostConstruct
    private void init() {
        // 创建消费组，MKSTREAM表示stream不存在时一并创建；组已存在时会抛异常，忽略即可
        try {
            stringRedisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            "stream.orders".getBytes(StandardCharsets.UTF_8),
                            "g1",
                            ReadOffset.from("0"),
                            true));
        } catch (Exception e) {
            log.debug("订单消费组已存在：{}", e.getMessage());
        }
        SECKILL_ORDER_EXECUTOR.submit(new OrderHandler());
    }

    @PreDestroy
    public void destroy() {
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdownNow();
    }

    private class OrderHandler implements Runnable {

        @Override
        public void run() {
            while (running) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    Order order = BeanUtil.fillBeanWithMap(value, new Order(), true);
                    // 3.处理订单（锁 + 事务 + 落库）
                    handleOrder(order);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    if (!running) {
                        break;
                    }
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (running) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    Order order = BeanUtil.fillBeanWithMap(value, new Order(), true);
                    // 3.处理订单（锁 + 事务 + 落库）
                    handleOrder(order);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    if (!running) {
                        break;
                    }
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    /**
     * 消费入口：分布式锁包住整个事务调用（锁在事务外层）。
     * proxy 调用返回即代表事务已提交，之后才走到 unlock，
     * 消除了"释放锁后、事务尚未提交"期间其他线程读到旧数据的并发缝隙。
     */
    private void handleOrder(Order order) {
        Long userId = order.getUserId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 关键：走代理调用，@Transactional 才会生效，不能写 this.createOrder(...)
            proxy.createOrder(order);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    /**
     * 只做数据库操作：count 校验 + 扣库存 + 建订单，由 @Transactional 保证原子性。
     * 注意：必须是 public，Spring 事务基于代理实现，private 方法上事务不生效。
     */
    @Transactional
    @Override
    public void createOrder(Order order) {
        Long userId = order.getUserId();
        Long productId = order.getProductId();

        // 5.一人一单第三道防线：数据库 count 校验（第一道 Lua 的 Set 判断、第二道 Redisson 锁）
        long count = query().eq("user_id", userId).eq("product_id", productId).count();
        // 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("不允许重复下单！");
            return;
        }

        // 6.乐观锁扣减库存（防超卖兜底）
        boolean success = seckillProductService.update()
                .setSql("stock = stock - 1")
                .eq("product_id", productId).gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }

        // 7.创建订单
        save(order);
    }

    @Override
    public Result orderState(Long productId) {
        Long userId = UserHolder.getUser().getId();
        // 1.抢购资格：Lua 阶段同步写入的 Redis Set（资格判断、预扣库存、发消息三合一）
        boolean qualified = Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(RedisConstants.SECKILL_ORDER_KEY + productId, userId.toString()));
        // 2.订单是否已异步落库
        long ordered = query().eq("user_id", userId).eq("product_id", productId).count();
        Map<String, Object> state = new HashMap<>();
        state.put("qualified", qualified);
        state.put("ordered", ordered > 0);
        return Result.ok(state);
    }

    @Override
    public Result queryMyOrders() {
        Long userId = UserHolder.getUser().getId();
        // 1.查询我的订单（新订单在前）
        List<Order> orders = query().eq("user_id", userId).orderByDesc("create_time").list();
        if (orders.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.批量查询商品与商户，组装展示信息（两段查询避免联表）
        List<Long> productIds = orders.stream().map(Order::getProductId).collect(Collectors.toList());
        Map<Long, Product> productMap = productService.listByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        List<Long> merchantIds = productMap.values().stream()
                .map(Product::getMerchantId).distinct().collect(Collectors.toList());
        Map<Long, Merchant> merchantMap = merchantIds.isEmpty() ? Map.of()
                : merchantService.listByIds(merchantIds).stream()
                        .collect(Collectors.toMap(Merchant::getId, m -> m));
        List<Map<String, Object>> result = orders.stream().map(o -> {
            Map<String, Object> vo = new HashMap<>();
            vo.put("orderId", o.getId());
            vo.put("productId", o.getProductId());
            Product p = productMap.get(o.getProductId());
            if (p != null) {
                vo.put("title", p.getTitle());
                vo.put("payValue", p.getPayValue());
                Merchant m = merchantMap.get(p.getMerchantId());
                vo.put("merchantName", m != null ? m.getName() : "");
            }
            vo.put("createTime", o.getCreateTime());
            vo.put("status", o.getStatus() == null ? 0 : o.getStatus());
            return vo;
        }).collect(Collectors.toList());
        return Result.ok(result);
    }

    @Override
    public Result seckillProduct(Long productId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                productId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.返回订单id
        return Result.ok(orderId);
    }
}
