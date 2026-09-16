package io.github.lu1j.rolloutcore.starter;

import io.github.lu1j.rolloutcore.sdk.RolloutCoreClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(RolloutCoreProperties.class)
@ConditionalOnProperty(prefix = "rolloutcore.sdk", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RolloutCoreAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RolloutCoreClient.class)
    public RolloutCoreClient rolloutCoreClient(RolloutCoreProperties properties) {
        return RolloutCoreClient.create(properties.toOptions());
    }
}
