package io.github.lu1j.rolloutcore.server.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CacheMetrics {
    private final Map<String, Counter> counters;
    public CacheMetrics(MeterRegistry registry) {
        counters = Stream.of("l1_hit", "l1_miss", "l2_hit", "l2_miss", "l2_error", "db_load", "negative_hit",
                "singleflight_join", "lkg_fallback", "cache_invalidation", "stale_fill_rejected")
                .collect(Collectors.toUnmodifiableMap(n -> n, n -> registry.counter("rolloutcore.cache." + n)));
    }
    public void increment(String name) { counters.get(name).increment(); }
}
