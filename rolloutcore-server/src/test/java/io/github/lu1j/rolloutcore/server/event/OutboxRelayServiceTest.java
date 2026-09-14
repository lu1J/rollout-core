package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.domain.OutboxEvent;
import io.github.lu1j.rolloutcore.server.mapper.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxRelayServiceTest {
    @Test void sendsBeforeMarkingSent() {
        var mapper = mock(OutboxEventMapper.class); var producer = mock(ConfigEventProducer.class);
        var event = new OutboxEvent(); event.setId(7L);
        when(mapper.findPending(20)).thenReturn(List.of(event)); when(mapper.markSent(eq(7L), any())).thenReturn(1);
        new OutboxRelayService(mapper, producer).relayPending();
        var order = inOrder(mapper, producer);
        order.verify(mapper).findPending(20); order.verify(producer).send(event);
        order.verify(mapper).markSent(eq(7L), any());
    }
    @Test void failedSendRemainsPendingAndNextPollRetries() {
        var mapper = mock(OutboxEventMapper.class); var producer = mock(ConfigEventProducer.class);
        var event = new OutboxEvent(); event.setId(7L);
        when(mapper.findPending(20)).thenReturn(List.of(event)); when(mapper.markSent(eq(7L), any())).thenReturn(1);
        doThrow(new IllegalStateException("offline")).doNothing().when(producer).send(event);
        var relay = new OutboxRelayService(mapper, producer); relay.relayPending();
        verify(mapper, never()).markSent(anyLong(), any());
        relay.relayPending(); verify(producer, times(2)).send(event); verify(mapper).markSent(eq(7L), any());
    }
    @Test void failedSentUpdatePropagatesForTransactionRollback() {
        var mapper = mock(OutboxEventMapper.class); var producer = mock(ConfigEventProducer.class);
        var event = new OutboxEvent(); event.setId(7L); when(mapper.findPending(20)).thenReturn(List.of(event));
        assertThrows(IllegalStateException.class, () -> new OutboxRelayService(mapper, producer).relayPending());
    }
}
