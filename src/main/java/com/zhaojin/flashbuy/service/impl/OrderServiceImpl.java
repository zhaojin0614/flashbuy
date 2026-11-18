package com.zhaojin.flashbuy.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.dto.OrderCloseMessage;
import com.zhaojin.flashbuy.dto.SeckillMessage;
import com.zhaojin.flashbuy.entity.Order;
import com.zhaojin.flashbuy.entity.Merchant;
import com.zhaojin.flashbuy.entity.Product;
import com.zhaojin.flashbuy.mapper.OrderMapper;
import com.zhaojin.flashbuy.service.IMerchantService;
import com.zhaojin.flashbuy.service.IProductReleaseService;
import com.zhaojin.flashbuy.service.IProductService;
import com.zhaojin.flashbuy.service.IOrderService;
import com.zhaojin.flashbuy.utils.RateLimiter;
import com.zhaojin.flashbuy.utils.RedisConstants;
import com.zhaojin.flashbuy.utils.RedisIdWorker;
import com.zhaojin.flashbuy.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /** 消费中标记 TTL：崩溃后 30s 自愈，需大于单次消费耗时、小于重试退避间隔 */
    private static final long CONSUMING_TTL_SECONDS = 30;

    /** 订单超时关单延迟级别（对应 broker.conf 定制的 messageDelayLevel，15 = 15m） */
    @Value("${order.close-delay-level:15}")
    private int closeDelayLevel;

    /** 入口令牌桶限流开关（压测时关闭以测裸链路） */
    @Value("${order.seckill-ratelimit.enabled:true}")
    private boolean seckillRatelimitEnabled;
    @Value("${order.seckill-ratelimit.api-capacity:200}")
    private long apiCapacity;
    @Value("${order.seckill-ratelimit.api-refill-per-sec:100}")
    private double apiRefillPerSec;
    @Value("${order.seckill-ratelimit.user-capacity:5}")
    private long userCapacity;
    @Value("${order.seckill-ratelimit.user-refill-per-sec:1}")
    private double userRefillPerSec;

    @Resource
    private RateLimiter rateLimiter;

    /**
     * RocketMQ 消费入口：SETNX 消费中标记 + 分布式锁包住整个事务调用（锁在事务外层）。
     * proxy 调用返回即代表事务已提交，之后才走到 unlock，
     * 消除了"释放锁后、事务尚未提交"期间其他线程读到旧数据的并发缝隙。
     *
     * 消费幂等三层：① SETNX 消费中标记（同一 orderId 的并发/重投消息让出）
     * ② createOrder 的订单号存在性 + active_flag 有效订单 count 校验
     * ③ tb_order 唯一索引 uk_order_user_product_active 兜底。
     *
     * 标记语义是"正在处理"（30s TTL 自愈）而非"已消费"：订单不存在且标记在时，
     * 无法区分"上一条正在处理"与"上一条崩在落库前"——所以非本人持有的标记绝不删除，
     * 只抛异常等 MQ 重试（退避 10s/30s 后标记已过期，重新处理）。
     * 锁获取失败同样抛异常 => RECONSUME_LATER，最多 16 次后进死信。
     */
    @Override
    public void consume(Order order) {
        Long userId = order.getUserId();
        // 0.消费幂等第一层：SETNX 消费中标记（TTL 30s，崩溃后自愈）
        String consumingKey = RedisConstants.SECKILL_CONSUMING_KEY + order.getId();
        boolean acquired = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(consumingKey, "1", CONSUMING_TTL_SECONDS, TimeUnit.SECONDS));
        if (!acquired) {
            // SETNX 失败 = 同一消息的并发投递或重投，上一条正在处理（或已处理完）
            // 按 orderId 判断是否已落库（不能只看 active_flag：已取消订单的消息重投同样要 ACK）
            Order existing = getById(order.getId());
            if (existing != null) {
                log.info("重复消息，订单已存在，幂等跳过 orderId={}", order.getId());
                return;
            }
            // 上一条还在处理中（或崩在落库前，等标记过期）：抛异常等 MQ 重试，绝不清别人的标记
            log.info("同一订单消息正在处理中，让出等待重试 orderId={}", order.getId());
            throw new IllegalStateException("consuming in progress, orderId=" + order.getId());
        }
        try {
            // 创建锁对象
            RLock redisLock = redissonClient.getLock("lock:order:" + userId);
            // 尝试获取锁
            boolean isLock = redisLock.tryLock();
            if (!isLock) {
                // 同一用户的并发消息在抢锁：抛异常交给 RocketMQ 重试，而不是静默丢单
                log.warn("一人一单锁获取失败，等待 MQ 重试 userId={}", userId);
                throw new IllegalStateException("lock busy: " + userId);
            }

            try {
                // 关键：走代理调用，@Transactional 才会生效，不能写 this.createOrder(...)
                boolean created = proxy.createOrder(order);
                if (created) {
                    log.info("订单消费落库成功 orderId={} productId={} userId={}",
                            order.getId(), order.getProductId(), order.getUserId());
                    // 落库成功 → 发延迟关单消息（超时未支付自动取消）。
                    // 发送失败只记日志不上抛：订单已存在，重试 consume 会走"已落库"分支
                    // 而不再发关单消息，上抛反而让重试链路把关单消息弄丢——残留未关单
                    // 订单由对账脚本兜底（见任务三记录的已知边界）。
                    sendOrderCloseMessage(order);
                }
            } finally {
                // 释放锁
                redisLock.unlock();
            }
        } catch (DuplicateKeyException e) {
            // 唯一索引拦截重复订单：事务已回滚，等价于“另一个请求已创建有效订单”，
            // 正常 ACK；不能让它进入 MQ 无限重试，也不能吞掉其他数据库异常。
            log.info("唯一索引拦截重复订单，幂等 ACK orderId={} userId={} productId={}",
                    order.getId(), userId, order.getProductId());
            stringRedisTemplate.delete(consumingKey);
            return;
        } catch (RuntimeException e) {
            // 只清理自己持有的标记，否则 MQ 重试的消息会被它挡在门外（等于丢了重试机会）
            stringRedisTemplate.delete(consumingKey);
            throw e;
        }
        // 成功后释放消费中标记（终态判定交给 DB count，重复消息会走"已落库"分支幂等 ACK）
        stringRedisTemplate.delete(consumingKey);
    }

    /**
     * 发送延迟关单消息：级别由 order.close-delay-level 配置（broker.conf 定制 15=15m），
     * 测试可用 --order.close-delay-level=1（1s）快速验证。
     */
    private void sendOrderCloseMessage(Order order) {
        try {
            MessageBuilder<OrderCloseMessage> builder = MessageBuilder.withPayload(
                    new OrderCloseMessage(order.getUserId(), order.getProductId(), order.getId()));
            rocketMQTemplate.syncSend(OrderCloseMessage.TOPIC, builder.build(), 3000, closeDelayLevel);
            log.info("延迟关单消息已发送 orderId={} delayLevel={}", order.getId(), closeDelayLevel);
        } catch (Exception e) {
            log.error("延迟关单消息发送失败，订单将滞留未支付状态，需对账脚本兜底 orderId={}", order.getId(), e);
        }
    }

    /**
     * 只做数据库操作：count 校验 + 扣库存 + 建订单，由 @Transactional 保证原子性。
     * 注意：必须是 public，Spring 事务基于代理实现，private 方法上事务不生效。
     *
     * @return 是否真正创建订单（false = 重复下单/库存不足，消费方据此决定是否发关单消息）
     */
    @Transactional
    @Override
    public boolean createOrder(Order order) {
        Long userId = order.getUserId();
        Long productId = order.getProductId();

        // 5.1 同一 orderId 的消息重投：订单已存在（含已取消）直接 ACK，避免重复扣库存
        if (getById(order.getId()) != null) {
            log.info("订单已存在，幂等跳过 orderId={}", order.getId());
            return false;
        }
        // 5.2 一人一单第三道防线：只统计“有效订单”，已取消/已退款不占复购名额
        long activeCount = query().eq("user_id", userId).eq("product_id", productId)
                .eq("active_flag", 1).count();
        if (activeCount > 0) {
            log.info("用户已有有效订单，拒绝重复下单 userId={} productId={}", userId, productId);
            return false;
        }

        // 6.乐观锁扣减库存（防超卖兜底）
        boolean success = seckillProductService.update()
                .setSql("stock = stock - 1")
                .eq("product_id", productId).gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return false;
        }

        // 7.创建订单
        save(order);
        return true;
    }

    /**
     * 延迟关单消费入口：状态机 CAS（status 1→4）保证幂等——
     * 只有"未支付"会被关闭；已支付/已关闭/不存在的订单直接 ACK 跳过。
     * 关单成功后回补库存并撤销资格（取消订单释放一人一单名额，允许用户重新抢购）。
     */
    @Override
    public void consumeClose(OrderCloseMessage message) {
        Long orderId = message.getOrderId();
        // 1.状态机 CAS：并发/重复关单消息只有一条能把 1 改成 4
        boolean closed = update().setSql("status = 4")
                .eq("id", orderId).eq("status", 1).update();
        if (!closed) {
            log.info("关单跳过：订单不存在或状态已流转 orderId={}", orderId);
            return;
        }
        // 2.回补 DB 权威库存（取消订单释放库存，直接加回，无超卖风险）
        seckillProductService.update()
                .setSql("stock = stock + 1")
                .eq("product_id", message.getProductId())
                .update();
        // 3.回补 Redis 预扣库存（key 可能已过期/被清理，存在才加）并撤销资格
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + message.getProductId();
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            stringRedisTemplate.opsForValue().increment(stockKey);
        }
        stringRedisTemplate.opsForSet().remove(RedisConstants.SECKILL_ORDER_KEY + message.getProductId(),
                message.getUserId().toString());
        log.info("订单超时关闭完成 orderId={} productId={} userId={}", orderId, message.getProductId(), message.getUserId());
    }

    @Override
    public Result orderState(Long productId) {
        Long userId = UserHolder.getUser().getId();
        // 1.抢购资格：Lua 阶段同步写入的 Redis Set（资格判断、预扣库存、写资格 Set 三合一）
        boolean qualified = Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(RedisConstants.SECKILL_ORDER_KEY + productId, userId.toString()));
        // 2.是否存在有效订单（已取消/已退款不占复购名额，用户可以重新抢）
        long ordered = query().eq("user_id", userId).eq("product_id", productId)
                .eq("active_flag", 1).count();
        Map<String, Object> state = new HashMap<>();
        state.put("qualified", qualified);
        state.put("ordered", ordered > 0);
        return Result.ok(state);
    }

    @Override
    public Result payOrder(Long orderId, Integer payType) {
        Long userId = UserHolder.getUser().getId();
        if (orderId == null) {
            return Result.fail("订单id不能为空");
        }
        int type = payType == null ? 1 : payType;
        if (type < 1 || type > 3) {
            return Result.fail("不支持的支付方式");
        }
        Order order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail("无权操作该订单");
        }
        // 已支付：幂等返回成功（重复点击支付按钮/网络重试）
        if (order.getStatus() != null && order.getStatus() == 2) {
            return Result.ok();
        }
        if (order.getStatus() == null || order.getStatus() != 1) {
            return Result.fail("订单已取消或状态已变化，无法支付");
        }
        // 状态机 CAS：只有待支付订单能被支付；与关单 CAS 竞争时最多一方成功
        boolean paid = update()
                .set("status", 2)
                .set("pay_time", LocalDateTime.now())
                .set("pay_type", type)
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", 1)
                .update();
        if (!paid) {
            Order latest = getById(orderId);
            if (latest != null && latest.getStatus() != null && latest.getStatus() == 2) {
                return Result.ok();
            }
            return Result.fail("订单已取消或状态已变化，无法支付");
        }
        return Result.ok();
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
            // orderId 超过 JS 安全整数范围，转字符串避免前端精度丢失后支付错单
            vo.put("orderId", o.getId().toString());
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
        // 0.入口双重令牌桶限流（先用户级——便宜的精准拒绝，再接口级——保护下游 Lua/MQ/DB）：
        //   用户级容量 5、1/s：防单用户刷接口；接口级容量 200、100/s：允许突发、限持续流量
        if (seckillRatelimitEnabled) {
            if (!rateLimiter.tryAcquire("rl:seckill:user:" + userId, userCapacity, userRefillPerSec, 1)) {
                return Result.fail("抢购太频繁，请稍后再试");
            }
            if (!rateLimiter.tryAcquire("rl:seckill:api", apiCapacity, apiRefillPerSec, 1)) {
                return Result.fail("当前抢购人数过多，请稍后再试");
            }
        }
        long orderId = redisIdWorker.nextId("order");
        // 1.事务消息（任务四）：先发半消息，Lua 预扣作为"本地事务"在半消息之后同步执行。
        //   时序：半消息 → Lua 预扣 → COMMIT/ROLLBACK；COMMIT 确认丢失由回查按资格 Set 兜底。
        //   相比任务一"Lua 先执行 + 同步发送 + 失败补偿"：预扣成功但消息丢失的窗口从结构上消除，
        //   补偿 Lua 整体退役（seckill_rollback.lua 已删除）。
        int[] luaResult = new int[1];   // holder：监听器把 Lua 返回码带回请求线程
        try {
            TransactionSendResult result = rocketMQTemplate.sendMessageInTransaction(
                    SeckillMessage.TOPIC,
                    MessageBuilder.withPayload(new SeckillMessage(userId, productId, orderId)).build(),
                    luaResult);
            // 2.按"本地事务"执行结果给用户响应（半消息写入失败会直接抛异常进入 catch）
            if (result.getLocalTransactionState() != LocalTransactionState.COMMIT_MESSAGE) {
                // ROLLBACK（库存不足/重复下单）或 UNKNOW（回查未决，保守返回失败）
                return Result.fail(luaResult[0] == 2 ? "不能重复下单" : "库存不足");
            }
        } catch (Exception e) {
            log.error("事务消息发送失败 productId={} userId={} orderId={}", productId, userId, orderId, e);
            return Result.fail("系统繁忙，请稍后重试");
        }
        // 3.返回订单id
        return Result.ok(orderId);
    }
}
