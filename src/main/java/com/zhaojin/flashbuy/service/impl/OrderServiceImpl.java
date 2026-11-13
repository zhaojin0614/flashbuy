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
import com.zhaojin.flashbuy.utils.RedisConstants;
import com.zhaojin.flashbuy.utils.RedisIdWorker;
import com.zhaojin.flashbuy.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
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

    /**
     * 补偿脚本：MQ 发送失败时回滚 Lua 预扣（库存 +1、SREM 资格），
     * 两条命令在一个脚本里原子完成，避免"库存已回补但资格还在"的半回滚状态
     */
    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    static {
        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /** 消费中标记 TTL：崩溃后 30s 自愈，需大于单次消费耗时、小于重试退避间隔 */
    private static final long CONSUMING_TTL_SECONDS = 30;

    /** 订单超时关单延迟级别（对应 broker.conf 定制的 messageDelayLevel，14 = 15m） */
    @Value("${order.close-delay-level:14}")
    private int closeDelayLevel;

    /**
     * RocketMQ 消费入口：SETNX 消费中标记 + 分布式锁包住整个事务调用（锁在事务外层）。
     * proxy 调用返回即代表事务已提交，之后才走到 unlock，
     * 消除了"释放锁后、事务尚未提交"期间其他线程读到旧数据的并发缝隙。
     *
     * 消费幂等三层：① SETNX 消费中标记（同一 orderId 的并发/重投消息让出）
     * ② createOrder 的 DB count 校验 ③ tb_order 唯一索引 uk_order_user_product 兜底。
     *
     * 标记语义是"正在处理"（30s TTL 自愈）而非"已消费"：count==0 且标记在时，
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
            long count = query().eq("user_id", userId).eq("product_id", order.getProductId()).count();
            if (count > 0) {
                // 上一条已提交落库——幂等 ACK，不再处理
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
        } catch (RuntimeException e) {
            // 只清理自己持有的标记，否则 MQ 重试的消息会被它挡在门外（等于丢了重试机会）
            stringRedisTemplate.delete(consumingKey);
            throw e;
        }
        // 成功后释放消费中标记（终态判定交给 DB count，重复消息会走"已落库"分支幂等 ACK）
        stringRedisTemplate.delete(consumingKey);
    }

    /**
     * 发送延迟关单消息：级别由 order.close-delay-level 配置（broker.conf 定制 14=15m），
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

        // 5.一人一单第三道防线：数据库 count 校验（第一道 Lua 的 Set 判断、第二道 Redisson 锁）
        long count = query().eq("user_id", userId).eq("product_id", productId).count();
        // 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("不允许重复下单！");
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
        // 1.执行lua脚本：资格判断 + 预扣库存 + 写资格 Set（原子，不含消息投递）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                productId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.同步发送 RocketMQ（生产者自动重试 2 次；SEND_OK = Broker 已写入，此后异步落库）
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(SeckillMessage.TOPIC,
                    new SeckillMessage(userId, productId, orderId));
            // 非 SEND_OK（如 FLUSH_DISK_TIMEOUT）同样按失败处理：补偿回滚；
            // 若消息实际已被投递，消费端 count 校验兜底幂等，不会重复建单
            if (!SendStatus.SEND_OK.equals(sendResult.getSendStatus())) {
                throw new IllegalStateException("MQ 发送状态异常: " + sendResult.getSendStatus());
            }
        } catch (Exception e) {
            log.error("MQ 发送失败，执行 Lua 预扣补偿回滚 productId={} userId={} orderId={}",
                    productId, userId, orderId, e);
            // 补偿原子回滚：库存 +1 与撤销资格一起完成
            stringRedisTemplate.execute(SECKILL_ROLLBACK_SCRIPT, Collections.emptyList(),
                    productId.toString(), userId.toString());
            return Result.fail("系统繁忙，请稍后重试");
        }
        // 4.返回订单id
        return Result.ok(orderId);
    }
}
