package io.github.lu1j.rolloutcore.server.cache;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AfterCommitCacheTest extends CacheFixture {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {}

    @Test void actualSpringListenerInvalidatesOnlyAfterCommit() throws Exception { verifyTransaction(false); }
    @Test void actualSpringListenerDoesNothingOnRollback() throws Exception { verifyTransaction(true); }

    private void verifyTransaction(boolean rollback) throws Exception {
        var connection = mock(Connection.class); var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        var tx = new DataSourceTransactionManager(dataSource); var cache = cache(k -> snapshot(5, true)); cache.get(key);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfig.class); context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> tx);
            context.registerBean(SnapshotCache.class, () -> cache); context.refresh();
            new TransactionTemplate(tx).executeWithoutResult(status -> {
                context.publishEvent(new ConfigChanged(key, 6));
                assertEquals(0, count("cache_invalidation")); assertEquals(SnapshotProvider.Source.L1, cache.get(key).source());
                if (rollback) status.setRollbackOnly();
            });
            assertEquals(rollback ? 0 : 1, count("cache_invalidation"));
            assertEquals(rollback ? 0 : 1, redis.invalidations);
            if (rollback) { verify(connection).rollback(); assertEquals(SnapshotProvider.Source.L1, cache.get(key).source()); }
            else verify(connection).commit();
            context.publishEvent(new ConfigChanged(key, 7));
            assertEquals(rollback ? 0 : 1, count("cache_invalidation"), "No transaction must not trigger fallback execution");
        }
    }
}
