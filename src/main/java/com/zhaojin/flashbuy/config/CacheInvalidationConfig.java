package com.zhaojin.flashbuy.config;

import com.zhaojin.flashbuy.service.MerchantCacheInvalidationSubscriber;
import com.zhaojin.flashbuy.service.MerchantCacheService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 订阅商户缓存失效频道，支持多实例 L1 及时失效。 */
@Configuration
public class CacheInvalidationConfig {

    @Bean
    public RedisMessageListenerContainer merchantCacheInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            MerchantCacheInvalidationSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(MerchantCacheService.CACHE_INVALIDATE_CHANNEL));
        return container;
    }
}
