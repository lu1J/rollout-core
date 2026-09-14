package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.OutboxEvent;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OutboxMapperTest {
    @Test void mapperParsesAndLocksPendingRowsWithConditionalSentTransition() throws Exception {
        var config = new Configuration();
        try (var input = getClass().getClassLoader().getResourceAsStream("mapper/OutboxEventMapper.xml")) {
            new XMLMapperBuilder(input, config, "mapper/OutboxEventMapper.xml", config.getSqlFragments()).parse();
        }
        String ns = OutboxEventMapper.class.getName() + ".";
        String pending = config.getMappedStatement(ns + "findPending").getBoundSql(Map.of("limit", 20)).getSql();
        assertTrue(pending.contains("WHERE status = 'PENDING'"));
        assertTrue(pending.contains("ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED"));
        var sent = config.getMappedStatement(ns + "markSent").getBoundSql(Map.of());
        assertTrue(sent.getSql().contains("AND status = 'PENDING'"));
        var insert = config.getMappedStatement(ns + "insert").getBoundSql(new OutboxEvent());
        assertEquals(10, insert.getParameterMappings().size());
    }
}
