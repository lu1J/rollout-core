package io.github.lu1j.rolloutcore.starter;

import io.github.lu1j.rolloutcore.sdk.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RolloutCoreAutoConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RolloutCoreAutoConfiguration.class));
    @Test void enabledByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(RolloutCoreClient.class));
    }
    @Test void enabledExplicitly() {
        runner.withPropertyValues("rolloutcore.sdk.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RolloutCoreClient.class));
    }
    @Test void disabledCreatesNoClientEvenWithInvalidConfiguration() {
        runner.withPropertyValues("rolloutcore.sdk.enabled=false", "rolloutcore.sdk.max-retries=-1")
                .run(context -> assertThat(context).doesNotHaveBean(RolloutCoreClient.class));
    }
    @Test void customBeanOverridesDefaultWithoutValidatingUnusedOptions() {
        var custom = mock(RolloutCoreClient.class);
        runner.withBean(RolloutCoreClient.class, () -> custom).withPropertyValues("rolloutcore.sdk.max-retries=-1")
                .run(context -> assertThat(context.getBean(RolloutCoreClient.class)).isSameAs(custom));
    }
    @Test void allPropertiesBind() {
        runner.withPropertyValues("rolloutcore.sdk.base-url=http://localhost:9090/root",
                "rolloutcore.sdk.connect-timeout=123ms", "rolloutcore.sdk.timeout=2s",
                "rolloutcore.sdk.max-retries=3", "rolloutcore.sdk.retry-delay=50ms",
                "rolloutcore.sdk.lkg-ttl=1m", "rolloutcore.sdk.lkg-capacity=12").run(context -> {
            var options = context.getBean(RolloutCoreProperties.class).toOptions();
            assertThat(options.baseUrl().toString()).isEqualTo("http://localhost:9090/root");
            assertThat(options.connectTimeout()).isEqualTo(Duration.ofMillis(123));
            assertThat(options.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(options.maxRetries()).isEqualTo(3);
            assertThat(options.retryDelay()).isEqualTo(Duration.ofMillis(50));
            assertThat(options.lkgTtl()).isEqualTo(Duration.ofMinutes(1));
            assertThat(options.lkgCapacity()).isEqualTo(12);
        });
    }
    @Test void invalidOptionsFailStartup() {
        runner.withPropertyValues("rolloutcore.sdk.max-retries=6")
                .run(context -> assertThat(context).hasFailed());
    }
}
