package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.domain.*;
import io.github.lu1j.rolloutcore.server.mapper.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import static org.mockito.Mockito.*;

abstract class ServiceFixture {
    protected ProjectMapper projects;
    protected EnvironmentMapper environments;
    protected FeatureFlagMapper flags;
    protected FlagVariantMapper variants;
    protected FlagEnvironmentConfigMapper configs;
    protected AuditLogMapper audits;
    protected OutboxEventMapper outbox;
    protected ControlPlaneService service;
    protected JsonMapper json;
    protected Project project;
    protected Environment environment;
    protected FeatureFlag flag;
    protected FlagVariant variant;
    protected FlagEnvironmentConfig config;
    protected org.springframework.context.ApplicationEventPublisher events;
    private ValidatorFactory validatorFactory;

    @BeforeEach
    void fixture() {
        projects = mock(ProjectMapper.class);
        environments = mock(EnvironmentMapper.class);
        flags = mock(FeatureFlagMapper.class);
        variants = mock(FlagVariantMapper.class);
        configs = mock(FlagEnvironmentConfigMapper.class);
        audits = mock(AuditLogMapper.class);
        outbox = mock(OutboxEventMapper.class);
        when(outbox.insert(any())).thenReturn(1);
        json = JsonMapper.builder().build();
        validatorFactory = Validation.buildDefaultValidatorFactory();
        events = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new ControlPlaneService(projects, environments, flags, variants, configs, audits,
                new InputRules(validatorFactory.getValidator()), json, events, outbox);
        project = new Project();
        project.setId(1L); project.setProjectKey("shop"); project.setName("Shop");
        environment = new Environment();
        environment.setId(2L); environment.setProjectId(1L); environment.setEnvKey("prod");
        flag = new FeatureFlag();
        flag.setId(3L); flag.setProjectId(1L); flag.setFlagKey("pay"); flag.setValueType(FlagValueType.BOOLEAN);
        variant = new FlagVariant();
        variant.setId(4L); variant.setFlagId(3L); variant.setVariantKey("old"); variant.setValueJson("false");
        config = new FlagEnvironmentConfig();
        config.setId(5L); config.setFlagId(3L); config.setEnvironmentId(2L); config.setDefaultVariantId(4L);
        config.setVersion(0); config.setEnabled(false); config.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(projects.findByKey("shop")).thenReturn(project);
        when(environments.findByKey(1, "prod")).thenReturn(environment);
        when(flags.findByKey(1, "pay")).thenReturn(flag);
        when(variants.findByKey(3, "old")).thenReturn(variant);
        when(configs.find(3, 2)).thenReturn(config);
        when(projects.insert(any())).thenAnswer(inv -> { inv.getArgument(0, Project.class).setId(1L); return 1; });
        when(environments.insert(any())).thenAnswer(inv -> { inv.getArgument(0, Environment.class).setId(2L); return 1; });
        when(flags.insert(any())).thenAnswer(inv -> { inv.getArgument(0, FeatureFlag.class).setId(3L); return 1; });
        when(variants.insert(any())).thenAnswer(inv -> { inv.getArgument(0, FlagVariant.class).setId(4L); return 1; });
        when(configs.insert(any())).thenAnswer(inv -> { inv.getArgument(0, FlagEnvironmentConfig.class).setId(5L); return 1; });
        when(configs.update(any(), anyLong())).thenReturn(1);
    }
    @AfterEach void closeValidator() { validatorFactory.close(); }
}
