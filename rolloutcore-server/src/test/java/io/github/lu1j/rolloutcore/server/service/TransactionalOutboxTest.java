package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.domain.*;
import io.github.lu1j.rolloutcore.server.cache.ConfigChanged;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Connection;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionalOutboxTest extends ServiceFixture {
    @Test void allConfigChangesPersistMatchingEvents() {
        service.createConfig("shop", "prod", "pay", new Commands.CreateConfig("old", true), "alice");
        service.updateConfig("shop", "prod", "pay", new Commands.UpdateConfig("old", true, 0L), "alice");
        service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(1L), false, "alice");
        service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(2L), true, "alice");
        when(variants.listByFlag(3)).thenReturn(java.util.List.of(variant));
        when(configs.updatePolicy(any(), anyLong())).thenReturn(1);
        service.updatePolicy("shop", "prod", "pay",
            new io.github.lu1j.rolloutcore.server.evaluation.EvaluationCommands.UpdatePolicy(3L, null, null), "alice");
        var capture = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, times(5)).insert(capture.capture());
        var ids = new java.util.HashSet<String>();
        for (int i = 0; i < 5; i++) {
            var event = capture.getAllValues().get(i);
            assertEquals(i, event.getVersion());
            assertEquals("shop", event.getProjectKey());
            assertEquals("prod", event.getEnvironmentKey());
            assertEquals("pay", event.getFlagKey());
            assertEquals("CONFIG_CHANGED", event.getEventType());
            assertEquals(OutboxStatus.PENDING, event.getStatus());
            assertEquals(event.getCreatedAt(), event.getUpdatedAt());
            ids.add(event.getEventId());
            assertEquals(i, json.readValue(event.getPayload(), ConfigChanged.class).latestVersion());
        }
        assertEquals(5, ids.size());
    }

    private Connection proxy() throws Exception {
        var connection = mock(Connection.class); var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(ds));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); advice.afterPropertiesSet();
        var factory = new ProxyFactory(service); factory.addAdvice(advice);
        service = (ControlPlaneService) factory.getProxy();
        return connection;
    }

    @Test void configAuditOutboxShareTransactionAndCommitOrder() throws Exception {
        var connection = proxy();
        when(outbox.insert(any())).thenAnswer(inv -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return 1;
        });
        service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(0L), true, "alice");
        var order = inOrder(configs, audits, outbox, events, connection);
        order.verify(configs).update(any(), eq(0L)); order.verify(audits).insert(any());
        order.verify(outbox).insert(any()); order.verify(events).publishEvent(any(Object.class));
        order.verify(connection).commit(); verify(connection, never()).rollback();
    }

    @Test void auditFailureRollsBackWithoutOutboxInsert() throws Exception {
        var connection = proxy();
        when(audits.insert(any())).thenThrow(new IllegalStateException("audit failed"));
        assertThrows(IllegalStateException.class, () ->
            service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(0L), true, "alice"));
        verifyNoInteractions(outbox, events); verify(connection).rollback(); verify(connection, never()).commit();
    }

    @Test void outboxFailureRollsBackConfigAndAudit() throws Exception {
        var connection = proxy();
        when(outbox.insert(any())).thenThrow(new IllegalStateException("outbox failed"));
        assertThrows(IllegalStateException.class, () ->
            service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(0L), true, "alice"));
        verify(configs).update(any(), eq(0L)); verify(audits).insert(any());
        verifyNoInteractions(events); verify(connection).rollback(); verify(connection, never()).commit();
    }

    @Test void failureAfterOutboxInsertRollsBackInsteadOfCommitting() throws Exception {
        var connection = proxy();
        doThrow(new IllegalStateException("event failure")).when(events).publishEvent(any(Object.class));
        assertThrows(IllegalStateException.class, () ->
            service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(0L), true, "alice"));
        verify(outbox).insert(any()); verify(connection).rollback(); verify(connection, never()).commit();
    }
}
