package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.FlagEnvironmentConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationPolicyPersistenceTest {
    private Configuration configuration;
    @BeforeEach void loadMapper() throws Exception {
        configuration = new Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        try (var input = getClass().getClassLoader().getResourceAsStream("mapper/FlagEnvironmentConfigMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/FlagEnvironmentConfigMapper.xml", configuration.getSqlFragments()).parse();
        }
    }
    @Test void policySqlIsConditionalAndDoesNotWriteSwitchSettings() {
        var sql = configuration.getMappedStatement(FlagEnvironmentConfigMapper.class.getName() + ".updatePolicy")
                .getBoundSql(Map.of("config", new FlagEnvironmentConfig(), "expectedVersion", 7L));
        assertEquals("UPDATE ff_flag_config SET evaluation_policy_json = ?, version = version + 1, updated_at = ? WHERE id = ? AND version = ?",
                sql.getSql().replaceAll("\\s+", " ").trim());
        assertEquals(List.of("config.evaluationPolicyJson", "config.updatedAt", "config.id", "expectedVersion"),
                sql.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        var ordinary = configuration.getMappedStatement(FlagEnvironmentConfigMapper.class.getName() + ".update")
                .getBoundSql(Map.of("config", new FlagEnvironmentConfig(), "expectedVersion", 7L));
        assertFalse(ordinary.getSql().contains("evaluation_policy_json"));
    }
    @Test void myBatisActuallyMapsJsonColumnToConfig() throws Exception {
        var mapped = configuration.getMappedStatement(FlagEnvironmentConfigMapper.class.getName() + ".find");
        var bound = mapped.getBoundSql(Map.of("flagId", 3L, "environmentId", 2L));
        assertTrue(bound.getSql().contains("evaluation_policy_json"));
        ResultSet result = mock(ResultSet.class); ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(metadata); when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("evaluation_policy_json");
        when(metadata.getColumnType(1)).thenReturn(Types.LONGVARCHAR);
        when(metadata.getColumnClassName(1)).thenReturn(String.class.getName());
        when(result.next()).thenReturn(true, false);
        when(result.getString("evaluation_policy_json")).thenReturn("{\"rules\":[]}");
        Statement statement = mock(Statement.class); when(statement.getResultSet()).thenReturn(result);
        var handler = new DefaultResultSetHandler(mock(Executor.class), mapped, null, null, bound, RowBounds.DEFAULT);
        List<Object> rows = handler.handleResultSets(statement);
        assertEquals("{\"rules\":[]}", ((FlagEnvironmentConfig) rows.getFirst()).getEvaluationPolicyJson());
    }
    @Test void v2OnlyAddsNullableJsonAndV1BytesRemainUnchanged() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/V2__add_evaluation_policy.sql")) {
            assertNotNull(input);
            assertEquals("ALTER TABLE ff_flag_config ADD COLUMN evaluation_policy_json JSON NULL;", new String(input.readAllBytes(), StandardCharsets.UTF_8).trim());
        }
        // Git blob digest recorded from the initial migration. Does not require Git at test runtime.
        try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/V1__init.sql")) {
            assertNotNull(input); byte[] bytes = input.readAllBytes();
            // Git normalizes CRLF in this repository; checksum protects the original SQL content.
            byte[] normalized = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + normalized.length + "\0").getBytes(StandardCharsets.UTF_8));
            assertEquals("67d5730a1f7b06c3a8065842d10b9f34e155a7fe", HexFormat.of().formatHex(digest.digest(normalized)));
        }
    }
}
