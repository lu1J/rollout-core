package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.service.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.lu1j.rolloutcore.server.cache.SnapshotProvider.Source.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCacheTest extends CacheFixture {
    @Test void firstLoadUsesDatabaseThenL1WithoutDatabaseOrRedis() {
        var calls = new AtomicInteger(); var good = snapshot(5, true);
        var cache = cache(k -> { calls.incrementAndGet(); return good; });
        assertEquals(DB, cache.get(key).source());
        assertSame(good, cache.get(key).snapshot()); assertEquals(L1, cache.get(key).source());
        assertEquals(1, calls.get()); assertEquals(1, redis.reads); assertEquals(1, redis.writes);
        assertEquals(2, count("l1_hit")); assertEquals(1, count("l1_miss"));
        assertEquals(1, count("l2_miss")); assertEquals(1, count("db_load"));
    }
    @Test void redisHitFillsL1AndBuildsLkg() {
        redis.put(key, 5, codec.encode(snapshot(5, true)), properties.l2Ttl());
        var cache = cache(k -> { throw new DataAccessResourceFailureException("DB offline"); });
        assertEquals(L2, cache.get(key).source()); assertEquals(L1, cache.get(key).source());
        advance(Duration.ofSeconds(11)); redis.failRead = true;
        assertEquals(LKG, cache.get(key).source()); assertEquals(1, count("l2_hit")); assertEquals(1, count("lkg_fallback"));
    }
    @Test void l1ExpiresIntoL2AndL2ExpiresIntoDb() {
        var calls = new AtomicInteger(); var cache = cache(k -> snapshot(calls.incrementAndGet(), true));
        cache.get(key); advance(Duration.ofSeconds(11)); assertEquals(L2, cache.get(key).source());
        advance(Duration.ofSeconds(60)); assertEquals(DB, cache.get(key).source()); assertEquals(2, calls.get());
    }
    @Test void redisReadAndWriteFailureStillReturnSuccessfulDbResult() {
        redis.failRead = true; redis.failWrite = true;
        var cache = cache(k -> snapshot(5, true));
        assertEquals(DB, cache.get(key).source()); assertEquals(L1, cache.get(key).source());
        assertEquals(2, count("l2_error")); assertEquals(1, count("db_load"));
    }
    @Test void corruptRedisJsonFallsBackWithout500() {
        redis.put(key, 0, "{broken", properties.l2Ttl());
        var cache = cache(k -> snapshot(5, true));
        assertEquals(DB, cache.get(key).source()); assertEquals(1, redis.invalidations);
        assertEquals(1, count("l2_error")); assertEquals(5, codec.decode(key, redis.payload).configVersion());
    }
    @Test void structurallyInvalidRedisSnapshotAlsoFallsBack() {
        redis.put(key, 0, "{\"schemaVersion\":1}", properties.l2Ttl());
        assertEquals(DB, cache(k -> snapshot(5, true)).get(key).source());
        assertEquals(1, count("l2_error"));
    }
    @Test void negativeCachesOnlyConfirmedNotFoundAndExpiresQuickly() {
        var calls = new AtomicInteger(); var cache = cache(k -> { calls.incrementAndGet(); throw BusinessException.notFound("Config"); });
        assertEquals(NEGATIVE, cache.get(key).source()); assertEquals(NEGATIVE, cache.get(key).source());
        assertEquals(1, calls.get()); assertEquals(1, count("negative_hit"));
        advance(Duration.ofSeconds(3)); cache.get(key); assertEquals(2, calls.get());
    }
    @Test void infrastructureFailureIsNotNegativeCachedAndFlightCanRetry() {
        var calls = new AtomicInteger(); var cache = cache(k -> {
            if (calls.incrementAndGet() == 1) throw new DataAccessResourceFailureException("offline");
            return snapshot(5, true);
        });
        assertThrows(DataAccessResourceFailureException.class, () -> cache.get(key));
        assertEquals(DB, cache.get(key).source()); assertEquals(2, calls.get()); assertEquals(0, count("negative_hit"));
    }
    @Test void createCommitClearsNegativeMarker() {
        var current = new AtomicReference<EvaluationSnapshot>();
        var cache = cache(k -> { if (current.get() == null) throw BusinessException.notFound("Config"); return current.get(); });
        cache.get(key); current.set(snapshot(0, true)); cache.afterCommit(new ConfigChanged(key, 0));
        assertEquals(DB, cache.get(key).source()); assertEquals(1, count("cache_invalidation"));
    }
    @Test void lkgFallbackDoesNotRefreshItsTtlOrFillL1() {
        var fail = new AtomicInteger(); var cache = cache(k -> {
            if (fail.get() > 0) throw new DataAccessResourceFailureException("DB offline"); return snapshot(5, true);
        });
        cache.get(key); fail.set(1); redis.failRead = true; advance(Duration.ofSeconds(11));
        assertEquals(LKG, cache.get(key).source()); assertEquals(LKG, cache.get(key).source());
        advance(Duration.ofMinutes(5));
        assertThrows(DataAccessResourceFailureException.class, () -> cache.get(key)); assertEquals(2, count("lkg_fallback"));
    }
    @Test void trueNotFoundNeverFallsBackToLkgAndRemovesIt() {
        var mode = new AtomicInteger(); var cache = cache(k -> {
            if (mode.get() == 1) throw BusinessException.notFound("Config");
            if (mode.get() == 2) throw new DataAccessResourceFailureException("offline");
            return snapshot(5, true);
        });
        cache.get(key); advance(Duration.ofSeconds(61)); mode.set(1);
        assertEquals(NEGATIVE, cache.get(key).source());
        mode.set(2); advance(Duration.ofSeconds(3));
        assertThrows(DataAccessResourceFailureException.class, () -> cache.get(key)); assertEquals(0, count("lkg_fallback"));
    }
    @Test void applicationDataErrorDoesNotUseLkg() {
        var mode = new AtomicInteger(); var cache = cache(k -> {
            if (mode.get() > 0) throw new DataIntegrityViolationException("invalid data"); return snapshot(5, true);
        });
        cache.get(key); mode.set(1); advance(Duration.ofSeconds(61));
        assertThrows(DataIntegrityViolationException.class, () -> cache.get(key)); assertEquals(0, count("lkg_fallback"));
    }
    @Test void killSwitchCommitClearsOldLkgEvenIfRedisInvalidationFails() {
        var mode = new AtomicInteger(); var cache = cache(k -> {
            if (mode.get() > 0) throw new DataAccessResourceFailureException("DB offline"); return snapshot(5, true);
        });
        cache.get(key); redis.failInvalidate = true; mode.set(1);
        cache.afterCommit(new ConfigChanged(key, 6));
        assertThrows(DataAccessResourceFailureException.class, () -> cache.get(key));
        assertEquals(0, count("lkg_fallback")); assertEquals(1, count("cache_invalidation"));
    }
    @Test void updatedDisabledSnapshotCanBecomeNewLkg() {
        var current = new AtomicReference<>(snapshot(5, true)); var cache = cache(k -> {
            if (current.get() == null) throw new DataAccessResourceFailureException("offline"); return current.get();
        });
        cache.get(key); current.set(snapshot(6, false)); cache.afterCommit(new ConfigChanged(key, 6));
        assertFalse(cache.get(key).snapshot().enabled()); current.set(null); advance(Duration.ofSeconds(11));
        var result = cache.get(key); assertEquals(LKG, result.source()); assertFalse(result.snapshot().enabled());
        assertEquals(6, result.snapshot().configVersion());
    }
    @Test void lowerVersionCannotFillAndKnownMinimumDoesNotRegress() {
        var calls = new AtomicInteger(); var cache = cache(k -> snapshot(calls.incrementAndGet() == 1 ? 5 : 6, false));
        cache.afterCommit(new ConfigChanged(key, 6)); cache.afterCommit(new ConfigChanged(key, 5));
        assertEquals(6, cache.get(key).snapshot().configVersion()); assertEquals(1, count("stale_fill_rejected"));
        assertEquals(6, cache.get(key).snapshot().configVersion());
    }
    @Test void redisDisabledMakesNoRedisCalls() {
        var disabled = new CacheProperties(false, properties.l1Ttl(), 100, properties.l2Ttl(), properties.negativeTtl(), properties.lkgTtl(), 100);
        var cache = new SnapshotCache(disabled, redis, codec, k -> snapshot(5, true), registry, nanos::get);
        cache.get(key); cache.afterCommit(new ConfigChanged(key, 6));
        assertEquals(0, redis.reads + redis.writes + redis.invalidations);
    }
    @Test void metricsHaveNoUserOrResourceTags() {
        var cache = cache(k -> snapshot(5, true)); cache.get(key); cache.get(key);
        for (var meter : registry.getMeters()) for (var tag : meter.getId().getTags()) {
            assertFalse(java.util.Set.of("userId", "projectKey", "flagKey", "environmentKey").contains(tag.getKey()));
            assertFalse(java.util.Set.of("shop", "prod", "pay").contains(tag.getValue()));
        }
    }
    @Test void slowDatabaseLoadCannotExtendRedisStalenessWindow() {
        var cache = cache(k -> { advance(Duration.ofSeconds(61)); return snapshot(5, true); });
        assertEquals(DB, cache.get(key).source()); assertEquals(0, redis.writes);
    }

    @Test void observedNewerVersionCannotBeReplacedByOlderDatabaseSnapshot() {
        var current = new AtomicReference<>(snapshot(6, false)); var cache = cache(k -> current.get());
        cache.get(key); advance(Duration.ofSeconds(61)); current.set(snapshot(5, true));
        assertThrows(org.springframework.dao.TransientDataAccessResourceException.class, () -> cache.get(key));
        assertEquals(3, count("stale_fill_rejected"));
        current.set(snapshot(6, false)); assertEquals(6, cache.get(key).snapshot().configVersion());
    }

    @Test void localStoresEvictUnderConfiguredMaximumSize() throws Exception {
        var small = new CacheProperties(false, properties.l1Ttl(), 2, properties.l2Ttl(), properties.negativeTtl(), properties.lkgTtl(), 2);
        var cache = new SnapshotCache(small, redis, codec, k -> {
            if (k.flagKey().startsWith("missing")) throw BusinessException.notFound("Config");
            return new EvaluationSnapshot(k, 0, true, "old", null, snapshot(0, true).variants());
        }, registry, nanos::get);
        for (int i = 0; i < 30; i++) {
            cache.get(new CacheKey("shop", "prod", "flag-" + i));
            cache.get(new CacheKey("shop", "prod", "missing-" + i));
        }
        for (String name : java.util.List.of("evaluation_l1", "evaluation_lkg", "evaluation_negative")) {
            var size = registry.get("cache.size").tag("cache", name).gauge();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (size.value() > 2 && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(size.value() <= 2, name + " must be bounded");
        }
    }
}
