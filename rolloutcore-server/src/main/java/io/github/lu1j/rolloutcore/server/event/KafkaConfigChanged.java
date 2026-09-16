package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.server.cache.CacheKey;
import io.github.lu1j.rolloutcore.server.cache.ConfigChanged;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned wire envelope; independent of mutable persistence entities. */
public record KafkaConfigChanged(String eventId, int schemaVersion, String projectKey,
        String environmentKey, String flagKey, long configVersion, Instant occurredAt) {
    public KafkaConfigChanged {
        if (eventId == null || !eventId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Invalid eventId");
        UUID.fromString(eventId);
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported event schemaVersion");
        new CacheKey(projectKey, environmentKey, flagKey);
        if (configVersion < 0) throw new IllegalArgumentException("Invalid configVersion");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
    // ':' is disallowed in all three resource keys, so the combination is unambiguous.
    public String recordKey() { return projectKey + ":" + environmentKey + ":" + flagKey; }
    public ConfigChanged change() { return new ConfigChanged(new CacheKey(projectKey, environmentKey, flagKey), configVersion); }
}
