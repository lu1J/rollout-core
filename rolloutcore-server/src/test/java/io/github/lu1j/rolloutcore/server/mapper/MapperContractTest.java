package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.FlagEnvironmentConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MapperContractTest {
    private Configuration configuration;

    @BeforeEach void loadXml() throws Exception {
        configuration = new Configuration();
        for (String name : new String[]{"Project", "Environment", "FeatureFlag", "FlagVariant",
                "FlagEnvironmentConfig", "AuditLog"}) {
            String resource = "mapper/" + name + "Mapper.xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
    }

    @Test void updateBindsExpectedVersionAndIncrementsInSql() {
        FlagEnvironmentConfig config = new FlagEnvironmentConfig(); config.setId(5L);
        BoundSql sql = configuration.getMappedStatement(FlagEnvironmentConfigMapper.class.getName() + ".update")
                .getBoundSql(Map.of("config", config, "expectedVersion", 7L));
        String normalized = sql.getSql().replaceAll("\\s+", " ").trim();
        assertEquals("UPDATE ff_flag_config SET enabled = ?, default_variant_id = ?, version = version + 1, updated_at = ? WHERE id = ? AND version = ?", normalized);
        assertEquals("expectedVersion", sql.getParameterMappings().getLast().getProperty());
        assertEquals("config.id", sql.getParameterMappings().get(3).getProperty());
    }

    @Test void configLookupUsesBothFlagAndEnvironment() {
        BoundSql sql = configuration.getMappedStatement(FlagEnvironmentConfigMapper.class.getName() + ".find")
                .getBoundSql(Map.of("flagId", 3L, "environmentId", 2L));
        assertTrue(sql.getSql().contains("flag_id = ? AND environment_id = ?"));
        assertEquals(2, sql.getParameterMappings().size());
    }

    @Test void variantLookupIsScopedToFlag() {
        BoundSql sql = configuration.getMappedStatement(FlagVariantMapper.class.getName() + ".findByKey")
                .getBoundSql(Map.of("flagId", 3L, "variantKey", "old"));
        assertTrue(sql.getSql().contains("flag_id = ? AND variant_key = ?"));
    }

    @Test void allInsertsRetrieveGeneratedIds() {
        for (Class<?> mapper : new Class<?>[]{ProjectMapper.class, EnvironmentMapper.class, FeatureFlagMapper.class,
                FlagVariantMapper.class, FlagEnvironmentConfigMapper.class, AuditLogMapper.class}) {
            assertArrayEquals(new String[]{"id"}, configuration.getMappedStatement(mapper.getName() + ".insert").getKeyProperties());
        }
    }

    @Test void auditQueryHasStableOrderingAndBoundPagination() {
        BoundSql sql = configuration.getMappedStatement(AuditLogMapper.class.getName() + ".list")
                .getBoundSql(Map.of("projectId", 1L, "limit", 50, "offset", 0));
        assertTrue(sql.getSql().contains("WHERE project_id = ? ORDER BY id DESC LIMIT ? OFFSET ?"));
    }
}
