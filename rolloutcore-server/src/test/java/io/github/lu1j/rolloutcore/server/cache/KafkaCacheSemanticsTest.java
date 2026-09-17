package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.event.*;
import io.github.lu1j.rolloutcore.server.service.BusinessException;
import io.github.lu1j.rolloutcore.server.mapper.OutboxEventMapper;
import io.github.lu1j.rolloutcore.domain.OutboxEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real codec + listener + versioned cache, deterministic DB loader; NOT a real Broker test. */
class KafkaCacheSemanticsTest extends CacheFixture {
    final KafkaConfigEventCodec events = new KafkaConfigEventCodec();
    final ConfigEventsProperties settings = new ConfigEventsProperties(ConfigEventsProperties.Transport.KAFKA,
            "instance-a","events",Duration.ofSeconds(1),0,Duration.ZERO,false);
    KafkaConfigChangedListener listener(SnapshotCache cache) {
        return new KafkaConfigChangedListener(events,new ConfigChangedConsumer(cache),settings, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
    ConsumerRecord<String,String> message(long version) {
        var event = new KafkaConfigChanged("12345678-1234-1234-1234-123456789abc",1,"shop","prod","pay",version,Instant.EPOCH);
        return new ConsumerRecord<>("events",0,version,event.recordKey(),events.encode(event));
    }
    @Test void sameEventDuplicateConservativelyInvalidatesWithoutWritingBusinessState() {
        var version = new AtomicLong(7); var reads = new AtomicInteger();
        var cache = cache(k -> { reads.incrementAndGet(); return snapshot(version.get(),true); });
        var adapter = listener(cache);
        cache.get(key);
        var record = message(7);
        adapter.onMessage(record); assertEquals(7,cache.get(key).snapshot().configVersion());
        adapter.onMessage(record); assertEquals(7,cache.get(key).snapshot().configVersion());
        assertEquals(2,count("cache_invalidation")); // equal versions are not a no-op
        assertEquals(3,reads.get()); assertEquals(7,version.get()); // loader is read-only
        assertEquals(7,redis.version);
    }
    @Test void versionSevenThenSixDoesNotInvalidateOrRegress() {
        var cache = cache(k -> snapshot(7,true)); var adapter = listener(cache);
        adapter.onMessage(message(7)); cache.get(key);
        adapter.onMessage(message(6));
        assertEquals(1,count("cache_invalidation"));
        var result = cache.get(key);
        assertEquals(SnapshotProvider.Source.L1,result.source()); assertEquals(7,result.snapshot().configVersion());
        assertEquals(7,redis.version);
    }
    @Test void versionSixThenSevenAdvancesAndReloads() {
        var version = new AtomicLong(6); var cache = cache(k -> snapshot(version.get(),true));
        var adapter = listener(cache);
        adapter.onMessage(message(6)); assertEquals(6,cache.get(key).snapshot().configVersion());
        version.set(7); adapter.onMessage(message(7));
        assertEquals(7,cache.get(key).snapshot().configVersion()); assertEquals(2,count("cache_invalidation"));
        assertEquals(7,redis.version);
    }
    @Test void createVersionZeroClearsNegativeCache() {
        var exists = new AtomicBoolean();
        var cache = cache(k -> { if (!exists.get()) throw BusinessException.notFound("config"); return snapshot(0,false); });
        assertEquals(SnapshotProvider.Source.NEGATIVE,cache.get(key).source());
        exists.set(true); listener(cache).onMessage(message(0));
        assertEquals(0,cache.get(key).snapshot().configVersion());
    }
    @Test void ackBeforeDbSentCrashReplayConvergesWithDuplicateEnvelope() {
        var cache = cache(k -> snapshot(7,true)); var adapter = listener(cache);
        var mapper = mock(OutboxEventMapper.class);
        var row = new OutboxEvent(); row.setId(42L); row.setEventId(events.decode(message(7).value()).eventId());
        when(mapper.findPending(20)).thenReturn(List.of(row));
        when(mapper.markSent(eq(42L),any())).thenReturn(0,1);
        var published = new AtomicInteger();
        ConfigEventProducer acknowledgedTransport = event -> { published.incrementAndGet(); adapter.onMessage(message(7)); };
        var relay = new OutboxRelayService(mapper,acknowledgedTransport, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertThrows(IllegalStateException.class,relay::relayPending);
        assertEquals(7,cache.get(key).snapshot().configVersion());
        relay.relayPending(); assertEquals(7,cache.get(key).snapshot().configVersion());
        assertEquals(2,published.get());
        verify(mapper,times(2)).findPending(20); verify(mapper,times(2)).markSent(eq(42L),any());
        verifyNoMoreInteractions(mapper); // consumption never INSERTs/UPDATEs business/config/audit rows
    }
}
