package com.zhaojin.flashbuy.service;

import com.zhaojin.flashbuy.dto.OrderCloseMessage;
import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.Order;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IOrderService extends IService<Order> {

    Result seckillProduct(Long productId);

    /**
     * 落库创建订单（@Transactional 生效需要走代理调用）
     *
     * @return 是否真正创建订单（false = 重复下单/库存不足，消费方据此决定是否发关单消息）
     */
    boolean createOrder(Order order);

    /**
     * RocketMQ 消费入口：Redisson 锁（一人一单）+ 事务落库。
     * 锁获取失败抛异常，由 MQ 自动重试（RECONSUME_LATER）而非静默丢单。
     */
    void consume(Order order);

    /**
     * 查询当前用户对某商品的抢购状态：
     * qualified = 已获得抢购资格（Lua 阶段同步写入 Redis Set，秒级即时）
     * ordered   = 订单已异步落库（DB）
     */
    Result orderState(Long productId);

    /**
     * 我的订单列表（带商品标题、商户名、金额，新订单在前）
     */
    Result queryMyOrders();

    /**
     * 演示支付：把订单从待支付 CAS 到已支付。
     * 支付成功后，延迟关单消息到达时状态机 CAS 会跳过，不再回补库存/撤销资格。
     */
    Result payOrder(Long orderId, Integer payType);

    /**
     * 延迟关单消费入口：状态机 CAS（status 1→4）+ 回补库存、撤销资格。
     * 状态机保证幂等：已支付/已关闭/不存在的订单直接跳过。
     */
    void consumeClose(OrderCloseMessage message);
}
