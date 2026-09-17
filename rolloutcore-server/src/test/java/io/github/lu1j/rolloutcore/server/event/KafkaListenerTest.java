package io.github.lu1j.rolloutcore.server.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.lu1j.rolloutcore.server.event.KafkaEventTestSupport.*;

class KafkaListenerTest {
    final KafkaConfigEventCodec codec = new KafkaConfigEventCodec();
    final ConfigChangedConsumer consumer = mock(ConfigChangedConsumer.class);
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final KafkaConfigChangedListener listener = new KafkaConfigChangedListener(codec,consumer,properties(), metrics);
    ConsumerRecord<String,String> record() {
        return new ConsumerRecord<>(properties().topic(),0,8,"shop:prod:pay",codec.encode(codec.fromOutbox(row())));
    }
    @Test void successDelegatesToExistingCacheConsumer() {
        when(consumer.consume(any())).thenReturn(true);
        listener.onMessage(record());
        verify(consumer).consume(codec.fromOutbox(row()).change());
        assertEquals(1, metrics.get("rolloutcore.kafka.consume").tag("outcome", "applied").counter().count());
    }
    @Test void staleEventIsAcknowledgedWithoutBeingCountedAsApplied() {
        when(consumer.consume(any())).thenReturn(false);
        listener.onMessage(record());
        assertEquals(1, metrics.get("rolloutcore.kafka.consume").tag("outcome", "stale").counter().count());
        assertNull(metrics.find("rolloutcore.kafka.consume").tag("outcome", "applied").counter());
        assertEquals(java.util.List.of(io.micrometer.core.instrument.Tag.of("outcome", "stale")),
                metrics.getMeters().getFirst().getId().getTags());
    }
    @Test void malformedMessageNeverReachesCache() {
        assertThrows(RuntimeException.class,() -> listener.onMessage(
                new ConsumerRecord<>(properties().topic(),0,8,"shop:prod:pay","{}")));
        verifyNoInteractions(consumer);
        assertEquals(1, metrics.get("rolloutcore.kafka.consume").tag("outcome", "invalid").counter().count());
    }
    @Test void mismatchedRecordKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,() -> listener.onMessage(
                new ConsumerRecord<>(properties().topic(),0,8,"wrong",record().value())));
        verifyNoInteractions(consumer);
    }
    @Test void localFailureIsRetriedThenSucceeds() {
        doThrow(new IllegalStateException("temporary")).doReturn(true).when(consumer).consume(any());
        listener.onMessage(record()); verify(consumer,times(2)).consume(any());
        assertEquals(1, metrics.get("rolloutcore.kafka.retry").counter().count());
        assertEquals(1, metrics.get("rolloutcore.kafka.consume").tag("outcome", "applied").counter().count());
    }
    @Test void retryExhaustionPropagatesSoContainerCannotAck() {
        doThrow(new IllegalStateException("failed")).when(consumer).consume(any());
        assertThrows(IllegalStateException.class,() -> listener.onMessage(record()));
        verify(consumer,times(3)).consume(any());
        assertEquals(2, metrics.get("rolloutcore.kafka.retry").counter().count());
        assertEquals(1, metrics.get("rolloutcore.kafka.consume").tag("outcome", "failure").counter().count());
    }
    @Test void retryInterruptionStopsAndPreservesFlag() {
        doThrow(new IllegalStateException("failed")).when(consumer).consume(any());
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class,() -> listener.onMessage(record()));
            assertTrue(Thread.currentThread().isInterrupted()); verify(consumer).consume(any());
            assertEquals(1, metrics.get("rolloutcore.kafka.consume").tag("outcome", "interrupted").counter().count());
        } finally { Thread.interrupted(); }
    }
}
