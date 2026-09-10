package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.domain.*;
import io.github.lu1j.rolloutcore.server.service.Commands.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises real Spring transaction advice / JDBC transaction management, with no external DB. */
class TransactionBoundaryTest extends ServiceFixture {
    private Connection connection;

    @BeforeEach void transactionProxy() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        interceptor.afterPropertiesSet();
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(interceptor);
        service = (ControlPlaneService) factory.getProxy();
    }

    private CreateFlag initialFlag() {
        return new CreateFlag("pay", "Pay", FlagValueType.BOOLEAN,
                List.of(new CreateVariant("old", json.readTree("false")),
                        new CreateVariant("new", json.readTree("true"))));
    }

    @Test void flagVariantsAndAuditShareOneActiveTransaction() throws Exception {
        doAnswer(inv -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            inv.getArgument(0, FeatureFlag.class).setId(3L);
            return 1;
        }).when(flags).insert(any());
        doAnswer(inv -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        }).when(variants).insert(any());
        when(audits.insert(any())).thenAnswer(inv -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        service.createFlag("shop", initialFlag(), "alice");
        var order = inOrder(flags, variants, audits, connection);
        order.verify(flags).insert(any());
        order.verify(variants, times(2)).insert(any());
        order.verify(audits).insert(any());
        order.verify(connection).commit();
        verify(connection, never()).rollback();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test void secondVariantFailureRollsBackWholeFlagOperation() throws Exception {
        doReturn(1).doThrow(new DuplicateKeyException("duplicate variant")).when(variants).insert(any());
        assertThrows(DuplicateKeyException.class, () -> service.createFlag("shop", initialFlag(), "alice"));
        verify(flags).insert(any());
        verify(variants, times(2)).insert(any());
        verify(audits, never()).insert(any());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void auditFailureRollsBackFlagAndBothVariants() throws Exception {
        when(audits.insert(any())).thenThrow(new DataIntegrityViolationException("audit unavailable"));
        assertThrows(DataIntegrityViolationException.class, () -> service.createFlag("shop", initialFlag(), "alice"));
        verify(variants, times(2)).insert(any());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void auditFailureRollsBackConfigUpdate() throws Exception {
        when(audits.insert(any())).thenThrow(new DataIntegrityViolationException("audit failure"));
        assertThrows(DataIntegrityViolationException.class, () -> service.updateConfig("shop", "prod", "pay",
                new UpdateConfig("old", true, 0L), "alice"));
        verify(configs).update(any(), eq(0L));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void optimisticConflictRollsBackAndDoesNotAudit() throws Exception {
        when(configs.update(any(), anyLong())).thenReturn(0);
        assertThrows(OptimisticLockConflictException.class, () -> service.setEnabled("shop", "prod", "pay",
                new SwitchFlag(0L), true, "alice"));
        verify(audits, never()).insert(any());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void projectWriteAndAuditCommitTogether() throws Exception {
        service.createProject(new CreateProject("shop", "Shop"), "alice");
        var order = inOrder(projects, audits, connection);
        order.verify(projects).insert(any());
        order.verify(audits).insert(any());
        order.verify(connection).commit();
    }

    @Test void invalidInputNeverWritesAndClosesTransaction() throws Exception {
        assertThrows(BusinessException.class, () -> service.createProject(new CreateProject("BAD", "Shop"), "alice"));
        verify(projects, never()).insert(any());
        verify(connection).rollback();
        verify(connection).close();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
