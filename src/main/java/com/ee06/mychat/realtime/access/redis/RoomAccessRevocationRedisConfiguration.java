package com.ee06.mychat.realtime.access.redis;

import com.ee06.mychat.realtime.config.SocketProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

@Configuration
public class RoomAccessRevocationRedisConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "wooms.socket", name = "access-revocation-enabled", havingValue = "true", matchIfMissing = true)
    RedisMessageListenerContainer roomAccessRevocationListenerContainer(
            RedisConnectionFactory connectionFactory, RoomAccessRevocationListener listener,
            SocketProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(Duration.ofSeconds(30).toMillis());
        container.addMessageListener(listener, new ChannelTopic(properties.getAccessRevocationChannel()));
        container.setErrorHandler(error -> org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("Room access revocation Redis listener unavailable; backend revalidation remains active (errorType={})",
                        error.getClass().getSimpleName()));
        return container;
    }
}
