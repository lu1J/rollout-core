package io.github.lu1j.rolloutcore.server.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("rolloutcore.cache")
public record CacheProperties(@DefaultValue("false") boolean redisEnabled,
        @DefaultValue("10s") Duration l1Ttl, @DefaultValue("10000") long l1MaximumSize,
        @DefaultValue("60s") Duration l2Ttl, @DefaultValue("2s") Duration negativeTtl,
        @DefaultValue("5m") Duration lkgTtl, @DefaultValue("10000") long lkgMaximumSize) {
    public CacheProperties {
        for (Duration ttl : new Duration[]{l1Ttl, l2Ttl, negativeTtl, lkgTtl}) {
            if (ttl == null || ttl.toMillis() < 1 || ttl.compareTo(Duration.ofDays(1)) > 0)
                throw new IllegalArgumentException("Cache TTL must be between 1ms and 1 day");
        }
        if (l1MaximumSize < 1 || lkgMaximumSize < 1) throw new IllegalArgumentException("Cache sizes must be positive");
        if (negativeTtl.compareTo(l1Ttl) >= 0 || negativeTtl.compareTo(l2Ttl) >= 0 || lkgTtl.compareTo(l1Ttl) <= 0)
            throw new IllegalArgumentException("Require negative TTL < positive TTLs and LKG TTL > L1 TTL");
    }
}
