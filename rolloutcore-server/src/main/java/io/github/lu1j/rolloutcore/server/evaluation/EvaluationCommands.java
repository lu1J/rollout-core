package io.github.lu1j.rolloutcore.server.evaluation;

import io.github.lu1j.rolloutcore.server.service.Commands;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import java.util.List;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy.*;

public final class EvaluationCommands {
    private EvaluationCommands() {}
    public record EvaluateRequest(
            @NotNull @Pattern(regexp = Commands.KEY) String projectKey,
            @NotNull @Pattern(regexp = Commands.KEY) String environmentKey,
            @NotNull @Pattern(regexp = Commands.KEY) String flagKey,
            @NotNull @Valid EvaluationContext context) {}
    public record UpdatePolicy(@NotNull @PositiveOrZero Long expectedVersion,
            @Size(max = 100) List<TargetingRule> rules,
            @Size(max = 100) List<RolloutAllocation> rollout) {
        public EvaluationPolicy policy() { return new EvaluationPolicy(rules, rollout); }
    }
    public record PolicyResponse(long version, EvaluationPolicy policy) {}
    public enum Reason { DISABLED, RULE_MATCH, PERCENTAGE_ROLLOUT, DEFAULT }
    public record EvaluationResponse(String flagKey, String variantKey, JsonNode value,
            Reason reason, long configVersion, Integer matchedRulePriority, Integer bucket) {}
}
