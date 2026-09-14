package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.server.cache.ConfigChanged;
import io.github.lu1j.rolloutcore.server.cache.SnapshotCache;
import org.springframework.stereotype.Component;
import java.util.Objects;

@Component
public class ConfigChangedConsumer {
    private final SnapshotCache cache;
    public ConfigChangedConsumer(SnapshotCache cache) { this.cache = cache; }
    /** Transport adapters call this after decoding and validating their message envelope. */
    public void consume(ConfigChanged event) {
        Objects.requireNonNull(event);
        cache.invalidateForVersion(event.key(), event.latestVersion());
    }
}
