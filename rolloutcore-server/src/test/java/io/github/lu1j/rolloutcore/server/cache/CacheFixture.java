package io.github.lu1j.rolloutcore.server.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

abstract class CacheFixture {
    final CacheKey key = new CacheKey("shop", "prod", "pay");
    final JsonMapper json = JsonMapper.builder().build();
    final SnapshotCodec codec = new SnapshotCodec(json);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final AtomicLong nanos = new AtomicLong();
    final FakeRedis redis = new FakeRedis();
    final CacheProperties properties = new CacheProperties(true, Duration.ofSeconds(10), 100,
            Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ofMinutes(5), 100);
    SnapshotCache cache(Function<CacheKey, EvaluationSnapshot> loader) {
        return new SnapshotCache(properties, redis, codec, loader, registry, nanos::get);
    }
    EvaluationSnapshot snapshot(long version, boolean enabled) {
        return new EvaluationSnapshot(key, version, enabled, "old", null,
                Map.of("old", new EvaluationSnapshot.VariantSnapshot("old", false),
                        "new", new EvaluationSnapshot.VariantSnapshot("new", true)));
    }
    double count(String name) { return registry.get("rolloutcore.cache." + name).counter().count(); }
    void advance(Duration duration) { nanos.addAndGet(duration.toNanos()); }
    @AfterEach void closeRegistry() { registry.close(); }

    // Deterministic Redis abstraction. This is not evidence that Lua ran on a real Redis server.
    class FakeRedis implements L2SnapshotStore {
        String payload;
        long version = -1;
        long expires;
        int reads, writes, invalidations;
        boolean failRead, failWrite, failInvalidate;
        @Override public synchronized Optional<String> get(CacheKey key) {
            reads++;
            if (failRead) throw new IllegalStateException("Redis unavailable");
            if (nanos.get() >= expires) { payload = null; version = -1; }
            return Optional.ofNullable(payload);
        }
        @Override public synchronized void put(CacheKey key, long incoming, String value, Duration ttl) {
            writes++;
            if (failWrite) throw new IllegalStateException("Redis write unavailable");
            if (nanos.get() >= expires) version = -1;
            if (incoming >= version) { payload = value; version = incoming; expires = nanos.get() + ttl.toNanos(); }
        }
        @Override public synchronized void invalidate(CacheKey key, long incoming, Duration ttl) {
            invalidations++;
            if (failInvalidate) throw new IllegalStateException("Redis invalidate unavailable");
            if (incoming >= version) { payload = null; version = incoming; expires = nanos.get() + ttl.toNanos(); }
        }
    }
}
