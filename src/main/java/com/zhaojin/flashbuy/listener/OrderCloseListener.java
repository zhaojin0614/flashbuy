package com.zhaojin.flashbuy.listener;

import com.zhaojin.flashbuy.dto.OrderCloseMessage;
import com.zhaojin.flashbuy.service.IOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 订单超时关单消费者：消费延迟消息（默认 15 分钟后到达），
 * 与秒杀下单消费者分离（独立 Topic + 独立消费组），互不抢占吞吐。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = OrderCloseMessage.TOPIC,
        consumerGroup = "flashbuy-order-close-consumer"
)
public class OrderCloseListener implements RocketMQListener<OrderCloseMessage> {

    @Resource
    private IOrderService orderService;

    @Override
    public void onMessage(OrderCloseMessage message) {
        orderService.consumeClose(message);
    }
}
