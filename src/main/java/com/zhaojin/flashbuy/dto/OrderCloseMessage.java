package com.zhaojin.flashbuy.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单超时关单消息体：订单落库成功后由消费者线程发出，
 * 以延迟消息（默认 15 分钟，order.close-delay-level 可配）到达关单消费者。
 *
 * 关单消费以状态机 CAS（status 1→4）保证幂等：重复消息、已支付订单都会被跳过。
 * 携带 userId/productId 是为了关单成功后回补 Redis 预扣库存并撤销资格。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCloseMessage {

    /** 订单超时关单 Topic（与秒杀下单 Topic 分离，消费者组也分离，互不影响） */
    public static final String TOPIC = "order_close_topic";

    private Long userId;
    private Long productId;
    private Long orderId;
}
