package io.github.lu1j.rolloutcore.server.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Duration;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {
    @Bean L2SnapshotStore l2SnapshotStore(CacheProperties properties, ObjectProvider<StringRedisTemplate> redis) {
        if (properties.redisEnabled()) return new RedisSnapshotStore(redis.getObject());
        return new L2SnapshotStore() {
            public Optional<String> get(CacheKey key) { return Optional.empty(); }
            public void put(CacheKey key, long version, String payload, Duration ttl) { }
            public void invalidate(CacheKey key, long version, Duration ttl) { }
        };
    }
    @Bean SnapshotCache snapshotCache(CacheProperties properties, L2SnapshotStore redis, SnapshotCodec codec,
            SnapshotRepository repository, MeterRegistry registry) {
        return new SnapshotCache(properties, redis, codec, repository::load, registry, Ticker.systemTicker());
    }
}
