package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.event.ConfigChangedConsumer;
import io.github.lu1j.rolloutcore.server.service.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigChangedConsumerTest extends CacheFixture {
    @Test void consumerDelegatesToVersionedInvalidation() {
        var cache = mock(SnapshotCache.class);
        new ConfigChangedConsumer(cache).consume(new ConfigChanged(key, 6));
        verify(cache).invalidateForVersion(key, 6);
    }
    @Test void duplicateAndOldEventsCannotRestoreOldVersion() {
        var version = new AtomicLong(5);
        var cache = cache(k -> snapshot(version.get(), true));
        assertEquals(5, cache.get(key).snapshot().configVersion());
        var consumer = new ConfigChangedConsumer(cache); version.set(6);
        assertTrue(consumer.consume(new ConfigChanged(key, 6)));
        assertTrue(consumer.consume(new ConfigChanged(key, 6)));
        assertEquals(6, cache.get(key).snapshot().configVersion());
        double invalidations = count("cache_invalidation");
        assertFalse(consumer.consume(new ConfigChanged(key, 5)));
        assertEquals(invalidations, count("cache_invalidation"));
        assertEquals(SnapshotProvider.Source.L1, cache.get(key).source());
        assertEquals(6, redis.version);
    }
    @Test void versionZeroCreationClearsNegative() {
        var exists = new java.util.concurrent.atomic.AtomicBoolean();
        var cache = cache(k -> {
            if (!exists.get()) throw BusinessException.notFound("configuration");
            return snapshot(0, false);
        });
        assertEquals(SnapshotProvider.Source.NEGATIVE, cache.get(key).source());
        exists.set(true);
        new ConfigChangedConsumer(cache).consume(new ConfigChanged(key, 0));
        assertEquals(0, cache.get(key).snapshot().configVersion());
    }
    @Test void remoteInvalidationClearsLkgAndL1() {
        var failed = new java.util.concurrent.atomic.AtomicBoolean();
        var cache = cache(k -> {
            if (failed.get()) throw new org.springframework.dao.DataAccessResourceFailureException("offline");
            return snapshot(5, true);
        });
        cache.get(key); failed.set(true);
        new ConfigChangedConsumer(cache).consume(new ConfigChanged(key, 6));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> cache.get(key));
    }
    @Test void twoIndependentInstancesReceiveChangeThroughProducerTestAdapter() {
        var version = new AtomicLong(5);
        var first = cache(k -> snapshot(version.get(), true));
        var second = cache(k -> snapshot(version.get(), true));
        first.get(key); second.get(key); version.set(6);
        var event = new io.github.lu1j.rolloutcore.domain.OutboxEvent();
        event.setPayload(json.writeValueAsString(new ConfigChanged(key, 6)));
        io.github.lu1j.rolloutcore.server.event.ConfigEventProducer transport = e -> {
            var change = json.readValue(e.getPayload(), ConfigChanged.class);
            new ConfigChangedConsumer(first).consume(change);
            new ConfigChangedConsumer(second).consume(change);
        };
        transport.send(event);
        assertEquals(6, first.get(key).snapshot().configVersion());
        assertEquals(6, second.get(key).snapshot().configVersion());
    }
}
