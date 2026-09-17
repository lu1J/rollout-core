package io.github.lu1j.rolloutcore.server.event;

import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import io.github.lu1j.rolloutcore.server.mapper.OutboxEventMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.lu1j.rolloutcore.server.event.KafkaEventTestSupport.*;

class KafkaProducerRelayTest {
    @SuppressWarnings("unchecked") final KafkaTemplate<String,String> template = mock(KafkaTemplate.class);
    final KafkaConfigEventCodec codec = new KafkaConfigEventCodec();
    final KafkaConfigEventProducer producer = new KafkaConfigEventProducer(template,codec,properties());
    final OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    OutboxRelayService relay() {
        when(mapper.findPending(20)).thenReturn(List.of(row()));
        when(mapper.markSent(eq(42L),any())).thenReturn(1);
        return new OutboxRelayService(mapper,producer, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
    @Test void sendsConfiguredTopicKeyAndVersionedPayload() {
        when(template.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(ack()));
        producer.send(row());
        verify(template).send(eq(properties().topic()),eq("shop:prod:pay"),eq(codec.encode(codec.fromOutbox(row()))));
    }
    @Test void relayCannotMarkSentBeforeBrokerAck() throws Exception {
        var pending = new CompletableFuture<SendResult<String,String>>();
        var submitted = new CountDownLatch(1);
        when(template.send(anyString(),anyString(),anyString())).thenAnswer(inv -> { submitted.countDown(); return pending; });
        var longer = new ConfigEventsProperties(ConfigEventsProperties.Transport.KAFKA,"a",properties().topic(),
                java.time.Duration.ofSeconds(5),0,java.time.Duration.ZERO,false);
        var relay = relay();
        relay = new OutboxRelayService(mapper,new KafkaConfigEventProducer(template,codec,longer), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var active = executor.submit(relay::relayPending);
            assertTrue(submitted.await(3,TimeUnit.SECONDS));
            verify(mapper,never()).markSent(anyLong(),any());
            pending.complete(ack());
            active.get(3,TimeUnit.SECONDS);
            verify(mapper).markSent(eq(42L),any());
        }
    }
    @Test void brokerFailureLeavesPendingThenNextRelayRetriesSameId() {
        when(template.send(anyString(),anyString(),anyString())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("broker offline")),
                CompletableFuture.completedFuture(ack()));
        var relay = relay();
        relay.relayPending(); verify(mapper,never()).markSent(anyLong(),any());
        relay.relayPending(); verify(mapper).markSent(eq(42L),any());
        verify(template,times(2)).send(eq(properties().topic()),eq("shop:prod:pay"),eq(codec.encode(codec.fromOutbox(row()))));
    }
    @Test void metadataOrSerializationFailureDoesNotMarkSent() {
        when(template.send(anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("metadata unavailable"));
        relay().relayPending();
        verify(mapper,never()).markSent(anyLong(),any());
    }
    @Test void timeoutDoesNotMarkSentEvenIfAckArrivesLater() {
        var pending = new CompletableFuture<SendResult<String,String>>();
        when(template.send(anyString(),anyString(),anyString())).thenReturn(pending);
        relay().relayPending();
        verify(mapper,never()).markSent(anyLong(),any());
        pending.complete(ack());
        verify(mapper,never()).markSent(anyLong(),any());
    }
    @Test void ackThenSentFailureRollsBackAndReplayMayDuplicate() {
        when(template.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(ack()));
        var relay = relay();
        when(mapper.markSent(eq(42L),any())).thenReturn(0,1);
        assertThrows(IllegalStateException.class,relay::relayPending);
        relay.relayPending();
        verify(template,times(2)).send(eq(properties().topic()),eq("shop:prod:pay"),eq(codec.encode(codec.fromOutbox(row()))));
        verify(mapper,times(2)).markSent(eq(42L),any());
    }
    @Test void interruptedSendPreservesInterruptFlag() {
        when(template.send(anyString(),anyString(),anyString())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class,() -> producer.send(row()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
