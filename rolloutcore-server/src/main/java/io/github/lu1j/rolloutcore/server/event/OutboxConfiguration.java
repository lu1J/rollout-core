package io.github.lu1j.rolloutcore.server.event;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxConfiguration {
    @Bean
    @ConditionalOnMissingBean(ConfigEventProducer.class)
    ConfigEventProducer configEventProducer() { return new LoggingProducer(); }
}
