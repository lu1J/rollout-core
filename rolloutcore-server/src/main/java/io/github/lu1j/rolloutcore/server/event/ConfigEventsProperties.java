package io.github.lu1j.rolloutcore.server.event;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("rolloutcore.events")
public record ConfigEventsProperties(@DefaultValue("logging") Transport transport,
        String instanceId, @DefaultValue("rolloutcore.config-events") String topic,
        @DefaultValue("5s") Duration sendTimeout, @DefaultValue("2") int consumerRetries,
        @DefaultValue("250ms") Duration consumerRetryDelay,
        @DefaultValue("true") boolean listenerAutoStartup) {
    public enum Transport { LOGGING, KAFKA }
    public ConfigEventsProperties {
        if (transport == null) throw new IllegalArgumentException("Transport is required");
        if (transport == Transport.KAFKA && (instanceId == null || !instanceId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")))
            throw new IllegalArgumentException("Kafka requires a unique stable rolloutcore.events.instance-id");
        if (topic == null || !topic.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,248}"))
            throw new IllegalArgumentException("Invalid config event topic");
        if (sendTimeout == null || sendTimeout.toMillis() < 100 || sendTimeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Send timeout must be 100ms..60s");
        if (consumerRetries < 0 || consumerRetries > 5 || consumerRetryDelay == null
                || consumerRetryDelay.isNegative() || consumerRetryDelay.compareTo(Duration.ofSeconds(5)) > 0)
            throw new IllegalArgumentException("Consumer retries must be 0..5 and delay 0..5s");
    }
    public String groupId() {
        if (transport != Transport.KAFKA) throw new IllegalStateException("Logging transport has no consumer group");
        return "rolloutcore-cache-" + instanceId;
    }
}
