package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.domain.*;
import io.github.lu1j.rolloutcore.server.mapper.*;
import io.github.lu1j.rolloutcore.server.service.Commands.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;

@Service
@Transactional(readOnly = true)
public class ControlPlaneService {
    private final ProjectMapper projects;
    private final EnvironmentMapper environments;
    private final FeatureFlagMapper flags;
    private final FlagVariantMapper variants;
    private final FlagEnvironmentConfigMapper configs;
    private final AuditLogMapper audits;
    private final InputRules rules;
    private final ObjectMapper json;

    public ControlPlaneService(ProjectMapper projects, EnvironmentMapper environments,
            FeatureFlagMapper flags, FlagVariantMapper variants, FlagEnvironmentConfigMapper configs,
            AuditLogMapper audits, InputRules rules, ObjectMapper json) {
        this.projects = projects;
        this.environments = environments;
        this.flags = flags;
        this.variants = variants;
        this.configs = configs;
        this.audits = audits;
        this.rules = rules;
        this.json = json;
    }

    @Transactional
    public Project createProject(CreateProject command, String operator) {
        rules.command(command);
        InputRules.operator(operator);
        Project project = new Project();
        project.setProjectKey(command.projectKey());
        project.setName(command.name());
        project.setStatus(ResourceStatus.ACTIVE);
        project.setCreatedAt(now());
        projects.insert(project);
        audit(project.getId(), null, null, operator, "PROJECT_CREATED", null, project);
        return project;
    }

    public Project getProject(String projectKey) {
        InputRules.key(projectKey);
        Project project = projects.findByKey(projectKey);
        if (project == null) throw BusinessException.notFound("Project");
        return project;
    }

    @Transactional
    public Environment createEnvironment(String projectKey, CreateEnvironment command, String operator) {
        rules.command(command);
        InputRules.operator(operator);
        Project project = getProject(projectKey);
        Environment environment = new Environment();
        environment.setProjectId(project.getId());
        environment.setEnvKey(command.envKey());
        environment.setName(command.name());
        environment.setStatus(ResourceStatus.ACTIVE);
        environment.setCreatedAt(now());
        environments.insert(environment);
        audit(project.getId(), environment.getId(), null, operator, "ENVIRONMENT_CREATED", null, environment);
        return environment;
    }

    public List<Environment> listEnvironments(String projectKey) {
        return environments.listByProject(getProject(projectKey).getId());
    }

    @Transactional
    public FeatureFlag createFlag(String projectKey, CreateFlag command, String operator) {
        rules.command(command);
        InputRules.operator(operator);
        Project project = getProject(projectKey);
        List<CreateVariant> initial = command.variants() == null ? List.of() : command.variants();
        // Validate every value before the first write; uniqueness is still enforced by MySQL.
        HashSet<String> seenVariantKeys = new HashSet<>();

        for (CreateVariant variant : initial) {
            if (!seenVariantKeys.add(variant.variantKey())) {
                throw BusinessException.validation(
                        "duplicate variantKey: " + variant.variantKey()
                );
            }

            InputRules.value(command.valueType(), variant.value());
        }
        FeatureFlag flag = new FeatureFlag();
        flag.setProjectId(project.getId());
        flag.setFlagKey(command.flagKey());
        flag.setName(command.name());
        flag.setValueType(command.valueType());
        flag.setStatus(ResourceStatus.ACTIVE);
        flag.setCreatedAt(now());
        flags.insert(flag);
        List<FlagVariant> created = initial.stream().map(v -> insertVariant(flag, v)).toList();
        audit(project.getId(), null, flag.getId(), operator, "FLAG_CREATED", null,
                Map.of("flag", flag, "variants", created));
        return flag;
    }

    public FeatureFlag getFlag(String projectKey, String flagKey) {
        return flag(getProject(projectKey).getId(), flagKey);
    }

    public List<FlagVariant> listVariants(String projectKey, String flagKey) {
        return variants.listByFlag(getFlag(projectKey, flagKey).getId());
    }

    @Transactional
    public FlagVariant createVariant(String projectKey, String flagKey, CreateVariant command, String operator) {
        rules.command(command);
        InputRules.operator(operator);
        FeatureFlag flag = getFlag(projectKey, flagKey);
        InputRules.value(flag.getValueType(), command.value());
        FlagVariant variant = insertVariant(flag, command);
        audit(flag.getProjectId(), null, flag.getId(), operator, "VARIANT_CREATED", null, variant);
        return variant;
    }

    @Transactional
    public FlagEnvironmentConfig createConfig(String projectKey, String envKey, String flagKey,
            CreateConfig command, String operator) {
        rules.command(command);
        InputRules.operator(operator);
        Scope scope = scope(projectKey, envKey, flagKey);
        FlagVariant variant = variant(scope.flag().getId(), command.defaultVariantKey());
        FlagEnvironmentConfig config = new FlagEnvironmentConfig();
        config.setFlagId(scope.flag().getId());
        config.setEnvironmentId(scope.environment().getId());
        config.setDefaultVariantId(variant.getId());
        config.setEnabled(command.enabled());
        config.setVersion(0);
        config.setCreatedAt(now());
        config.setUpdatedAt(config.getCreatedAt());
        configs.insert(config);
        audit(scope.projectId(), config.getEnvironmentId(), config.getFlagId(), operator,
                "CONFIG_CREATED", null, config);
        return config;
    }

    public FlagEnvironmentConfig getConfig(String projectKey, String envKey, String flagKey) {
        return config(scope(projectKey, envKey, flagKey));
    }

    @Transactional
    public FlagEnvironmentConfig updateConfig(String projectKey, String envKey, String flagKey,
            UpdateConfig command, String operator) {
        rules.command(command);
        InputRules.operator(operator);
        Scope scope = scope(projectKey, envKey, flagKey);
        FlagEnvironmentConfig current = config(scope);
        FlagVariant variant = variant(scope.flag().getId(), command.defaultVariantKey());
        return update(scope, current, command.enabled(), variant.getId(), command.expectedVersion(),
                operator, "CONFIG_UPDATED");
    }

    @Transactional
    public FlagEnvironmentConfig setEnabled(String projectKey, String envKey, String flagKey,
            SwitchFlag command, boolean enabled, String operator) {
        rules.command(command);
        InputRules.operator(operator);
        Scope scope = scope(projectKey, envKey, flagKey);
        FlagEnvironmentConfig current = config(scope);
        return update(scope, current, enabled, current.getDefaultVariantId(), command.expectedVersion(),
                operator, enabled ? "FLAG_ENABLED" : "FLAG_DISABLED");
    }

    public List<AuditLog> listAudits(String projectKey, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw BusinessException.validation("limit must be 1-100 and offset must be non-negative");
        }
        return audits.list(getProject(projectKey).getId(), limit, offset);
    }

    private FlagEnvironmentConfig update(Scope scope, FlagEnvironmentConfig current, boolean enabled,
            Long variantId, long expectedVersion, String operator, String operation) {
        // Reject stale reads too: their audit 'before' snapshot would describe the wrong version.
        if (current.getVersion() != expectedVersion) throw new OptimisticLockConflictException();
        String before = json.writeValueAsString(current);
        current.setEnabled(enabled);
        current.setDefaultVariantId(variantId);
        current.setUpdatedAt(now());
        // The conditional SQL update is the authority for concurrent writers.
        if (configs.update(current, expectedVersion) == 0) throw new OptimisticLockConflictException();
        current.setVersion(expectedVersion + 1);
        auditJson(scope.projectId(), current.getEnvironmentId(), current.getFlagId(), operator,
                operation, before, json.writeValueAsString(current));
        return current;
    }

    private FlagVariant insertVariant(FeatureFlag flag, CreateVariant command) {
        FlagVariant variant = new FlagVariant();
        variant.setFlagId(flag.getId());
        variant.setVariantKey(command.variantKey());
        variant.setValueJson(json.writeValueAsString(command.value()));
        variant.setCreatedAt(now());
        variants.insert(variant);
        return variant;
    }

    private FeatureFlag flag(long projectId, String flagKey) {
        InputRules.key(flagKey);
        FeatureFlag flag = flags.findByKey(projectId, flagKey);
        if (flag == null || !Objects.equals(flag.getProjectId(), projectId)) {
            throw BusinessException.notFound("Flag");
        }
        return flag;
    }

    private Scope scope(String projectKey, String envKey, String flagKey) {
        Project project = getProject(projectKey);
        InputRules.key(envKey);
        Environment environment = environments.findByKey(project.getId(), envKey);
        if (environment == null || !Objects.equals(environment.getProjectId(), project.getId())) {
            throw BusinessException.notFound("Environment");
        }
        return new Scope(project.getId(), environment, flag(project.getId(), flagKey));
    }

    private FlagVariant variant(long flagId, String variantKey) {
        InputRules.key(variantKey);
        FlagVariant variant = variants.findByKey(flagId, variantKey);
        if (variant == null || !Objects.equals(variant.getFlagId(), flagId)) {
            throw BusinessException.notFound("Variant");
        }
        return variant;
    }

    private FlagEnvironmentConfig config(Scope scope) {
        FlagEnvironmentConfig config = configs.find(scope.flag().getId(), scope.environment().getId());
        if (config == null) throw BusinessException.notFound("Config");
        return config;
    }

    private void audit(Long projectId, Long environmentId, Long flagId, String operator,
            String operation, Object before, Object after) {
        auditJson(projectId, environmentId, flagId, operator, operation,
                before == null ? null : json.writeValueAsString(before), json.writeValueAsString(after));
    }

    private void auditJson(Long projectId, Long environmentId, Long flagId, String operator,
            String operation, String before, String after) {
        AuditLog audit = new AuditLog();
        audit.setProjectId(projectId);
        audit.setEnvironmentId(environmentId);
        audit.setFlagId(flagId);
        audit.setOperatorName(operator);
        audit.setOperation(operation);
        audit.setBeforeJson(before);
        audit.setAfterJson(after);
        audit.setCreatedAt(now());
        audits.insert(audit);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private record Scope(Long projectId, Environment environment, FeatureFlag flag) {}
}
