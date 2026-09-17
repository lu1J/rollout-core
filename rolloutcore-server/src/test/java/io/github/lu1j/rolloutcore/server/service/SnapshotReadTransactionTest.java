package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.server.cache.*;
import io.github.lu1j.rolloutcore.server.evaluation.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotReadTransactionTest extends ServiceFixture {
    @Test void onlyDatabaseMissOpensTransactionAndL1HitDoesNotAcquireConnection() throws Exception {
        var dataSource = mock(DataSource.class); var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        var interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); interceptor.afterPropertiesSet();
        var proxy = new ProxyFactory(new SnapshotRepository(service, json)); proxy.addAdvice(interceptor);
        var repository = (SnapshotRepository) proxy.getProxy();
        when(variants.listByFlag(3)).thenAnswer(inv -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly()); return List.of(variant);
        });
        var properties = new CacheProperties(false, Duration.ofSeconds(10), 100, Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ofMinutes(5), 100);
        var registry = new SimpleMeterRegistry();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var cache = new SnapshotCache(properties, mock(L2SnapshotStore.class), new SnapshotCodec(json), repository::load, registry,
                    com.github.benmanes.caffeine.cache.Ticker.systemTicker());
            var evaluation = new EvaluationService(cache, new InputRules(factory.getValidator()), new RuleEngine(), new StableBucketService(), json, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            var request = new EvaluationCommands.EvaluateRequest("shop", "prod", "pay", new EvaluationContext("u", null, null, null, null));
            var first = evaluation.evaluate(request); assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); verify(connection).commit(); verify(connection).close();
            clearInvocations(dataSource, connection, projects, environments, flags, configs, variants);
            assertEquals(first, evaluation.evaluate(request));
            verifyNoInteractions(dataSource, connection, projects, environments, flags, configs, variants);
        } finally { registry.close(); }
    }
}
