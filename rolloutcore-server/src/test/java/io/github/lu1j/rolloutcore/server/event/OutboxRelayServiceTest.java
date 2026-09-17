package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.domain.OutboxEvent;
import io.github.lu1j.rolloutcore.server.mapper.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxRelayServiceTest {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    @Test void sendsBeforeMarkingSent() {
        var mapper = mock(OutboxEventMapper.class); var producer = mock(ConfigEventProducer.class);
        var event = new OutboxEvent(); event.setId(7L);
        when(mapper.findPending(20)).thenReturn(List.of(event)); when(mapper.markSent(eq(7L), any())).thenReturn(1);
        new OutboxRelayService(mapper, producer, metrics).relayPending();
        assertEquals(1, metrics.get("rolloutcore.outbox.send").tag("outcome", "success").counter().count());
        var order = inOrder(mapper, producer);
        order.verify(mapper).findPending(20); order.verify(producer).send(event);
        order.verify(mapper).markSent(eq(7L), any());
    }
    @Test void failedSendRemainsPendingAndNextPollRetries() {
        var mapper = mock(OutboxEventMapper.class); var producer = mock(ConfigEventProducer.class);
        var event = new OutboxEvent(); event.setId(7L);
        when(mapper.findPending(20)).thenReturn(List.of(event)); when(mapper.markSent(eq(7L), any())).thenReturn(1);
        doThrow(new IllegalStateException("offline")).doNothing().when(producer).send(event);
        var relay = new OutboxRelayService(mapper, producer, metrics); relay.relayPending();
        assertEquals(1, metrics.get("rolloutcore.outbox.send").tag("outcome", "failure").counter().count());
        assertEquals(0, metrics.get("rolloutcore.outbox.send").tag("outcome", "success").counter().count());
        verify(mapper, never()).markSent(anyLong(), any());
        relay.relayPending(); verify(producer, times(2)).send(event); verify(mapper).markSent(eq(7L), any());
        assertEquals(1, metrics.get("rolloutcore.outbox.send").tag("outcome", "success").counter().count());
    }
    @Test void failedSentUpdatePropagatesForTransactionRollback() {
        var mapper = mock(OutboxEventMapper.class); var producer = mock(ConfigEventProducer.class);
        var event = new OutboxEvent(); event.setId(7L); when(mapper.findPending(20)).thenReturn(List.of(event));
        assertThrows(IllegalStateException.class, () -> new OutboxRelayService(mapper, producer, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).relayPending());
    }
}
