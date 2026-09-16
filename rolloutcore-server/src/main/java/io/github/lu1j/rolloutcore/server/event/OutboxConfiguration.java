package io.github.lu1j.rolloutcore.server.event;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@org.springframework.boot.context.properties.EnableConfigurationProperties(ConfigEventsProperties.class)
public class OutboxConfiguration {
    @Bean
    @ConditionalOnMissingBean(ConfigEventProducer.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "rolloutcore.events.transport", havingValue = "logging", matchIfMissing = true)
    ConfigEventProducer configEventProducer() { return new LoggingProducer(); }
}
