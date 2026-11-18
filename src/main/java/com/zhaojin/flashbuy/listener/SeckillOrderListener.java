package com.zhaojin.flashbuy.listener;

import com.zhaojin.flashbuy.dto.SeckillMessage;
import com.zhaojin.flashbuy.entity.Order;
import com.zhaojin.flashbuy.service.IOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 秒杀订单消费者：RocketMQ 推模式容器托管线程，
 * 替代原 Redis Stream 的自建 ExecutorService + XREADGROUP 循环 + 手工 XACK/PEL 补偿。
 *
 * 消费语义由返回/异常表达：
 * - 正常返回 = CONSUME_SUCCESS（等价旧链路的 XACK）
 * - 抛出异常 = RECONSUME_LATER，RocketMQ 按退避间隔自动重试（默认最多 16 次），
 *   仍失败进入死信 Topic %DLQ%flashbuy-order-consumer——取代旧的 handlePendingList 手工补偿
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = SeckillMessage.TOPIC,
        consumerGroup = "flashbuy-order-consumer"
)
public class SeckillOrderListener implements RocketMQListener<SeckillMessage> {

    @Resource
    private IOrderService orderService;

    @Override
    public void onMessage(SeckillMessage message) {
        Order order = new Order();
        order.setId(message.getOrderId());
        order.setUserId(message.getUserId());
        order.setProductId(message.getProductId());
        // 抛出异常 => RocketMQ 自动重试；createOrder 的 orderId/active_flag 校验与唯一索引兜底幂等
        orderService.consume(order);
    }
}
