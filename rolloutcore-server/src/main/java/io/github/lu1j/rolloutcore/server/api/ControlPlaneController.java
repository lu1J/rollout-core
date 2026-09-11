package io.github.lu1j.rolloutcore.server.api;

import io.github.lu1j.rolloutcore.domain.*;
import io.github.lu1j.rolloutcore.server.service.ControlPlaneService;
import io.github.lu1j.rolloutcore.server.service.Commands.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import io.github.lu1j.rolloutcore.server.evaluation.EvaluationCommands.*;

@RestController
@RequestMapping("/api/v1")
public class ControlPlaneController {
    private final ControlPlaneService service;
    private final ObjectMapper json;

    public ControlPlaneController(ControlPlaneService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Project createProject(@Valid @RequestBody CreateProject command,
            @RequestHeader("X-Operator") String operator) {
        return service.createProject(command, operator);
    }

    @GetMapping("/projects/{projectKey}")
    public Project getProject(@PathVariable String projectKey) {
        return service.getProject(projectKey);
    }

    @PostMapping("/projects/{projectKey}/environments")
    @ResponseStatus(HttpStatus.CREATED)
    public Environment createEnvironment(@PathVariable String projectKey,
            @Valid @RequestBody CreateEnvironment command, @RequestHeader("X-Operator") String operator) {
        return service.createEnvironment(projectKey, command, operator);
    }

    @GetMapping("/projects/{projectKey}/environments")
    public List<Environment> listEnvironments(@PathVariable String projectKey) {
        return service.listEnvironments(projectKey);
    }

    @PostMapping("/projects/{projectKey}/flags")
    @ResponseStatus(HttpStatus.CREATED)
    public FeatureFlag createFlag(@PathVariable String projectKey, @Valid @RequestBody CreateFlag command,
            @RequestHeader("X-Operator") String operator) {
        return service.createFlag(projectKey, command, operator);
    }

    @GetMapping("/projects/{projectKey}/flags/{flagKey}")
    public FeatureFlag getFlag(@PathVariable String projectKey, @PathVariable String flagKey) {
        return service.getFlag(projectKey, flagKey);
    }

    @PostMapping("/projects/{projectKey}/flags/{flagKey}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    public VariantResponse createVariant(@PathVariable String projectKey, @PathVariable String flagKey,
            @Valid @RequestBody CreateVariant command, @RequestHeader("X-Operator") String operator) {
        return variantResponse(service.createVariant(projectKey, flagKey, command, operator));
    }

    @GetMapping("/projects/{projectKey}/flags/{flagKey}/variants")
    public List<VariantResponse> listVariants(@PathVariable String projectKey, @PathVariable String flagKey) {
        return service.listVariants(projectKey, flagKey).stream().map(this::variantResponse).toList();
    }

    @PostMapping("/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config")
    @ResponseStatus(HttpStatus.CREATED)
    public FlagEnvironmentConfig createConfig(@PathVariable String projectKey, @PathVariable String envKey,
            @PathVariable String flagKey, @Valid @RequestBody CreateConfig command,
            @RequestHeader("X-Operator") String operator) {
        return service.createConfig(projectKey, envKey, flagKey, command, operator);
    }

    @GetMapping("/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config")
    public FlagEnvironmentConfig getConfig(@PathVariable String projectKey, @PathVariable String envKey,
            @PathVariable String flagKey) {
        return service.getConfig(projectKey, envKey, flagKey);
    }

    @PutMapping("/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config")
    public FlagEnvironmentConfig updateConfig(@PathVariable String projectKey, @PathVariable String envKey,
            @PathVariable String flagKey, @Valid @RequestBody UpdateConfig command,
            @RequestHeader("X-Operator") String operator) {
        return service.updateConfig(projectKey, envKey, flagKey, command, operator);
    }

    @PostMapping("/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/enable")
    public FlagEnvironmentConfig enable(@PathVariable String projectKey, @PathVariable String envKey,
            @PathVariable String flagKey, @Valid @RequestBody SwitchFlag command,
            @RequestHeader("X-Operator") String operator) {
        return service.setEnabled(projectKey, envKey, flagKey, command, true, operator);
    }

    @PostMapping("/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/disable")
    public FlagEnvironmentConfig disable(@PathVariable String projectKey, @PathVariable String envKey,
            @PathVariable String flagKey, @Valid @RequestBody SwitchFlag command,
            @RequestHeader("X-Operator") String operator) {
        return service.setEnabled(projectKey, envKey, flagKey, command, false, operator);
    }

    @GetMapping("/audits")
    public List<AuditResponse> audits(@RequestParam String projectKey,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return service.listAudits(projectKey, limit, offset).stream().map(a -> new AuditResponse(
                a.getId(), a.getProjectId(), a.getEnvironmentId(), a.getFlagId(),
                a.getOperatorName(), a.getOperation(), a.getBeforeJson() == null ? null : json.readTree(a.getBeforeJson()),
                json.readTree(a.getAfterJson()), a.getCreatedAt())).toList();
    }

    @PutMapping("/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/evaluation-policy")
    public PolicyResponse updatePolicy(@PathVariable String projectKey, @PathVariable String envKey,
            @PathVariable String flagKey, @Valid @RequestBody UpdatePolicy command,
            @RequestHeader("X-Operator") String operator) {
        return service.updatePolicy(projectKey, envKey, flagKey, command, operator);
    }

    private VariantResponse variantResponse(FlagVariant v) {
        return new VariantResponse(v.getId(), v.getFlagId(), v.getVariantKey(), json.readTree(v.getValueJson()));
    }
    public record VariantResponse(Long id, Long flagId, String variantKey, JsonNode value) {}
    public record AuditResponse(Long id, Long projectId, Long environmentId, Long flagId,
            String operatorName, String operation, JsonNode before, JsonNode after,
            java.time.LocalDateTime createdAt) {}
}
