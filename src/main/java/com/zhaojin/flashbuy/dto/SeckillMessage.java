package com.zhaojin.flashbuy.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀下单消息体：Lua 预扣资格成功后，由请求线程同步发送到 RocketMQ。
 *
 * orderId 在 Lua 执行前由 RedisIdWorker 生成并随消息传递，
 * 消费端用它作为订单主键落库（tb_order.id 为 IdType.INPUT），
 * 保证"预扣-发送-落库"三段引用同一个订单号，也天然成为幂等依据之一。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage {

    /** 秒杀订单 Topic：producer 与 consumer 共用同一常量，避免字符串散落 */
    public static final String TOPIC = "seckill_order_topic";

    private Long userId;
    private Long productId;
    private Long orderId;
}
