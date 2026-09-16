package io.github.lu1j.rolloutcore.server.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.lu1j.rolloutcore.server.event.KafkaEventTestSupport.*;

class KafkaListenerTest {
    final KafkaConfigEventCodec codec = new KafkaConfigEventCodec();
    final ConfigChangedConsumer consumer = mock(ConfigChangedConsumer.class);
    final KafkaConfigChangedListener listener = new KafkaConfigChangedListener(codec,consumer,properties());
    ConsumerRecord<String,String> record() {
        return new ConsumerRecord<>(properties().topic(),0,8,"shop:prod:pay",codec.encode(codec.fromOutbox(row())));
    }
    @Test void successDelegatesToExistingCacheConsumer() {
        listener.onMessage(record());
        verify(consumer).consume(codec.fromOutbox(row()).change());
    }
    @Test void malformedMessageNeverReachesCache() {
        assertThrows(RuntimeException.class,() -> listener.onMessage(
                new ConsumerRecord<>(properties().topic(),0,8,"shop:prod:pay","{}")));
        verifyNoInteractions(consumer);
    }
    @Test void mismatchedRecordKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,() -> listener.onMessage(
                new ConsumerRecord<>(properties().topic(),0,8,"wrong",record().value())));
        verifyNoInteractions(consumer);
    }
    @Test void localFailureIsRetriedThenSucceeds() {
        doThrow(new IllegalStateException("temporary")).doNothing().when(consumer).consume(any());
        listener.onMessage(record()); verify(consumer,times(2)).consume(any());
    }
    @Test void retryExhaustionPropagatesSoContainerCannotAck() {
        doThrow(new IllegalStateException("failed")).when(consumer).consume(any());
        assertThrows(IllegalStateException.class,() -> listener.onMessage(record()));
        verify(consumer,times(3)).consume(any());
    }
    @Test void retryInterruptionStopsAndPreservesFlag() {
        doThrow(new IllegalStateException("failed")).when(consumer).consume(any());
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class,() -> listener.onMessage(record()));
            assertTrue(Thread.currentThread().isInterrupted()); verify(consumer).consume(any());
        } finally { Thread.interrupted(); }
    }
}
