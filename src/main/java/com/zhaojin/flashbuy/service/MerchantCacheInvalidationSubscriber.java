package com.zhaojin.flashbuy.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 接收商户缓存失效广播，删除本机 Caffeine L1。 */
@Slf4j
@Component
public class MerchantCacheInvalidationSubscriber implements MessageListener {

    @Resource
    private MerchantCacheService merchantCacheService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            String[] parts = body.split(":", 2);
            if (parts.length == 0 || parts[0].isBlank()) {
                return;
            }
            Long id = Long.valueOf(parts[0]);
            Long typeId = parts.length > 1 && !parts[1].isBlank() ? Long.valueOf(parts[1]) : null;
            merchantCacheService.invalidateLocal(id, typeId);
        } catch (NumberFormatException e) {
            log.warn("忽略非法缓存失效消息: {}", body);
        }
    }
}
