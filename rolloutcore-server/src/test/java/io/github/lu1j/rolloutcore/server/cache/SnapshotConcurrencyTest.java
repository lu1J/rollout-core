package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.service.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotConcurrencyTest extends CacheFixture {
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private void awaitJoins(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (count("singleflight_join") < expected && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(expected, count("singleflight_join"));
    }

    @Test void twentyConcurrentMissesLoadDatabaseExactlyOnce() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var expected = snapshot(5, true);
        var cache = cache(k -> { calls.incrementAndGet(); entered.countDown(); await(release); return expected; });
        var pool = Executors.newFixedThreadPool(20);
        try {
            var results = new ArrayList<Future<SnapshotProvider.LoadResult>>();
            for (int i = 0; i < 20; i++) results.add(pool.submit(() -> cache.get(key)));
            await(entered); awaitJoins(19); release.countDown();
            for (var result : results) assertSame(expected, result.get(5, TimeUnit.SECONDS).snapshot());
            assertEquals(1, calls.get()); assertEquals(1, redis.reads);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void failedFlightReleasesAllWaitersAndAllowsAnotherLoad() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var cache = cache(k -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); await(release); throw new DataAccessResourceFailureException("offline"); }
            return snapshot(5, true);
        });
        var pool = Executors.newFixedThreadPool(10);
        try {
            var results = new ArrayList<Future<SnapshotProvider.LoadResult>>();
            for (int i = 0; i < 10; i++) results.add(pool.submit(() -> cache.get(key)));
            await(entered); awaitJoins(9); release.countDown();
            for (var result : results) assertInstanceOf(DataAccessResourceFailureException.class,
                    assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(1, calls.get());
            assertEquals(5, cache.get(key).snapshot().configVersion()); assertEquals(2, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void commitBetweenReadAndFillRejectsOldVersionAndReloads() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var cache = cache(k -> {
            if (calls.incrementAndGet() == 1) { var old = snapshot(5, true); entered.countDown(); await(release); return old; }
            return snapshot(6, false);
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> cache.get(key)); await(entered);
            cache.afterCommit(new ConfigChanged(key, 6)); release.countDown();
            var result = pending.get(5, TimeUnit.SECONDS);
            assertEquals(6, result.snapshot().configVersion()); assertFalse(result.snapshot().enabled());
            assertEquals(6, cache.get(key).snapshot().configVersion());
            assertEquals(6, codec.decode(key, redis.payload).configVersion());
            assertEquals(2, calls.get()); assertEquals(1, count("stale_fill_rejected"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void notFoundReadBeforeCreateCannotRepopulateNegativeMarker() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var cache = cache(k -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); await(release); throw BusinessException.notFound("Config"); }
            return snapshot(0, true);
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> cache.get(key)); await(entered);
            cache.afterCommit(new ConfigChanged(key, 0)); release.countDown();
            assertEquals(0, pending.get(5, TimeUnit.SECONDS).snapshot().configVersion());
            assertEquals(SnapshotProvider.Source.L1, cache.get(key).source()); assertEquals(2, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
