package io.github.lu1j.rolloutcore.server.cache;

import java.time.Duration;
import java.util.Optional;

public interface L2SnapshotStore {
    Optional<String> get(CacheKey key);
    void put(CacheKey key, long version, String payload, Duration ttl);
    void invalidate(CacheKey key, long latestVersion, Duration ttl);
}
