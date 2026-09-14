package io.github.lu1j.rolloutcore.server.cache;

import com.github.benmanes.caffeine.cache.*;
import io.github.lu1j.rolloutcore.server.service.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;

/** Cache-aside with bounded values, JVM single-flight and striped generation fences. */
public class SnapshotCache implements SnapshotProvider {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotCache.class);
    private final Cache<CacheKey, EvaluationSnapshot> l1;
    private final Cache<CacheKey, EvaluationSnapshot> lkg;
    private final Cache<CacheKey, Boolean> negative;
    private final Cache<CacheKey, Long> minimumVersions;
    private final ConcurrentHashMap<CacheKey, CompletableFuture<LoadResult>> flights = new ConcurrentHashMap<>();
    private final Fence[] fences = new Fence[256];
    private final CacheProperties properties;
    private final L2SnapshotStore redis;
    private final SnapshotCodec codec;
    private final Function<CacheKey, EvaluationSnapshot> loader;
    private final CacheMetrics metrics;
    private final Ticker ticker;

    public SnapshotCache(CacheProperties properties, L2SnapshotStore redis, SnapshotCodec codec,
            Function<CacheKey, EvaluationSnapshot> loader, MeterRegistry registry, Ticker ticker) {
        this.properties = properties; this.redis = redis; this.codec = codec; this.loader = loader; this.ticker = ticker;
        this.metrics = new CacheMetrics(registry);
        l1 = cache(properties.l1MaximumSize(), properties.l1Ttl());
        lkg = cache(properties.lkgMaximumSize(), properties.lkgTtl());
        negative = cache(properties.l1MaximumSize(), properties.negativeTtl());
        minimumVersions = cache(properties.l1MaximumSize(), properties.lkgTtl().plus(properties.l2Ttl()));
        for (int i = 0; i < fences.length; i++) fences[i] = new Fence();
        CaffeineCacheMetrics.monitor(registry, l1, "evaluation_l1");
        CaffeineCacheMetrics.monitor(registry, lkg, "evaluation_lkg");
        CaffeineCacheMetrics.monitor(registry, negative, "evaluation_negative");
    }
    private <V> Cache<CacheKey, V> cache(long max, Duration ttl) {
        return Caffeine.newBuilder().maximumSize(max).expireAfterWrite(ttl).ticker(ticker).recordStats().build();
    }
    private Fence fence(CacheKey key) { return fences[(key.hashCode() & Integer.MAX_VALUE) % fences.length]; }
    private long minimum(CacheKey key) { Long v = minimumVersions.getIfPresent(key); return v == null ? 0 : v; }

    @Override public LoadResult get(CacheKey key) {
        Fence fence = fence(key);
        CompletableFuture<LoadResult> future;
        boolean leader;
        synchronized (fence) {
            var snapshot = l1.getIfPresent(key);
            if (snapshot != null) { metrics.increment("l1_hit"); return new LoadResult(snapshot, Source.L1); }
            metrics.increment("l1_miss");
            if (negative.getIfPresent(key) != null) {
                metrics.increment("negative_hit"); return new LoadResult(null, Source.NEGATIVE);
            }
            future = new CompletableFuture<>();
            var existing = flights.putIfAbsent(key, future);
            leader = existing == null;
            if (!leader) { future = existing; metrics.increment("singleflight_join"); }
        }
        if (!leader) {
            try { return future.join(); }
            catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException cause) throw cause;
                if (e.getCause() instanceof Error cause) throw cause;
                throw e;
            }
        }
        try {
            return load(key, fence, future);
        } catch (RuntimeException | Error e) {
            synchronized (fence) { future.completeExceptionally(e); flights.remove(key, future); }
            throw e;
        } finally {
            synchronized (fence) { flights.remove(key, future); }
        }
    }

    private LoadResult load(CacheKey key, Fence fence, CompletableFuture<LoadResult> future) {
        for (int attempt = 0; attempt < 3; attempt++) {
            long generation;
            boolean readRedis;
            long started = ticker.read();
            synchronized (fence) {
                generation = fence.generation;
                readRedis = properties.redisEnabled() && started >= fence.bypassRedisUntil;
            }
            EvaluationSnapshot snapshot = readRedis ? readRedis(key) : null;
            Source source = snapshot == null ? Source.DB : Source.L2;
            synchronized (fence) {
                if (snapshot != null && snapshot.configVersion() < minimum(key)) snapshot = null;
            }
            if (snapshot == null) {
                source = Source.DB;
                try { metrics.increment("db_load"); snapshot = loader.apply(key); }
                catch (BusinessException e) {
                    if (e.getStatus() != 404 || !"resource_not_found".equals(e.getCode())) throw e;
                    synchronized (fence) {
                        if (generation != fence.generation) { metrics.increment("stale_fill_rejected"); continue; }
                        negative.put(key, true); lkg.invalidate(key); l1.invalidate(key);
                        return complete(key, future, new LoadResult(null, Source.NEGATIVE));
                    }
                } catch (RuntimeException e) {
                    if (!(e instanceof DataAccessResourceFailureException || e instanceof TransientDataAccessException
                            || e instanceof CannotCreateTransactionException)) throw e;
                    synchronized (fence) {
                        if (generation != fence.generation) { metrics.increment("stale_fill_rejected"); continue; }
                        var good = lkg.getIfPresent(key);
                        if (good == null || good.configVersion() < minimum(key)) throw e;
                        metrics.increment("lkg_fallback");
                        LOG.warn("Evaluation using bounded LKG after database infrastructure failure");
                        return complete(key, future, new LoadResult(good, Source.LKG));
                    }
                }
            }
            synchronized (fence) {
                if (generation != fence.generation || snapshot.configVersion() < minimum(key)) {
                    metrics.increment("stale_fill_rejected"); continue;
                }
                // Serialize Redis fill with local invalidation. Network operations have short configured timeouts.
                if (source == Source.DB && properties.redisEnabled()) {
                    long remaining = properties.l2Ttl().toNanos() - (ticker.read() - started);
                    if (remaining > 0) writeRedis(key, snapshot, Duration.ofNanos(remaining));
                }
                negative.invalidate(key);
                minimumVersions.put(key, Math.max(minimum(key), snapshot.configVersion()));
                l1.put(key, snapshot); lkg.put(key, snapshot);
                return complete(key, future, new LoadResult(snapshot, source));
            }
        }
        throw new TransientDataAccessResourceException("Configuration changed repeatedly during snapshot load");
    }

    // Called under the stripe lock: publication, waiter completion and flight removal share one ordering with invalidation.
    private LoadResult complete(CacheKey key, CompletableFuture<LoadResult> future, LoadResult result) {
        future.complete(result); flights.remove(key, future); return result;
    }

    private EvaluationSnapshot readRedis(CacheKey key) {
        try {
            var payload = redis.get(key);
            if (payload.isEmpty()) { metrics.increment("l2_miss"); return null; }
            EvaluationSnapshot snapshot;
            try { snapshot = codec.decode(key, payload.get()); }
            catch (RuntimeException corrupt) {
                metrics.increment("l2_error"); LOG.warn("Invalid evaluation snapshot in Redis; falling back to database");
                invalidateRedis(key, 0); return null;
            }
            metrics.increment("l2_hit"); return snapshot;
        } catch (RuntimeException e) { redisError(); return null; }
    }
    private void writeRedis(CacheKey key, EvaluationSnapshot snapshot, Duration ttl) {
        try { redis.put(key, snapshot.configVersion(), codec.encode(snapshot), ttl); }
        catch (RuntimeException e) { redisError(); }
    }
    private void invalidateRedis(CacheKey key, long version) {
        try { redis.invalidate(key, version, properties.l2Ttl()); }
        catch (RuntimeException e) { redisError(); }
    }
    private void redisError() {
        metrics.increment("l2_error"); LOG.warn("Evaluation Redis operation failed; database remains authoritative");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(ConfigChanged event) {
        invalidateForVersion(event.key(), event.latestVersion());
    }

    public void invalidateForVersion(CacheKey key, long version) {
        java.util.Objects.requireNonNull(key);
        if (version < 0) throw new IllegalArgumentException("Configuration version must be non-negative");
        Fence fence = fence(key);
        synchronized (fence) {
            // Equal versions still invalidate: a version-0 creation must clear negative cache,
            // and observing a snapshot is not the same as processing its invalidation.
            if (version < minimum(key)) return;
            fence.generation++;
            minimumVersions.put(key, Math.max(minimum(key), version));
            // Conservative bounded-memory safety net even if the per-key watermark is evicted or Redis deletion fails.
            // Old fills have at most L2 TTL from load start; do not read this stripe's L2 during that window.
            fence.bypassRedisUntil = ticker.read() + properties.l2Ttl().toNanos();
            l1.invalidate(key); negative.invalidate(key); lkg.invalidate(key);
            metrics.increment("cache_invalidation");
            if (properties.redisEnabled()) invalidateRedis(key, version);
        }
    }
    private static final class Fence { long generation; long bypassRedisUntil; }
}
