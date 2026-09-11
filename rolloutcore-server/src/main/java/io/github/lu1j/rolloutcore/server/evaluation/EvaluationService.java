package io.github.lu1j.rolloutcore.server.evaluation;

import io.github.lu1j.rolloutcore.domain.FlagVariant;
import io.github.lu1j.rolloutcore.server.service.BusinessException;
import io.github.lu1j.rolloutcore.server.service.ControlPlaneService;
import io.github.lu1j.rolloutcore.server.service.InputRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Objects;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationCommands.*;

@Service
public class EvaluationService {
    private final ControlPlaneService control;
    private final InputRules inputs;
    private final RuleEngine rules;
    private final StableBucketService buckets;
    private final ObjectMapper json;

    public EvaluationService(ControlPlaneService control, InputRules inputs, RuleEngine rules,
            StableBucketService buckets, ObjectMapper json) {
        this.control = control;
        this.inputs = inputs;
        this.rules = rules;
        this.buckets = buckets;
        this.json = json;
    }

    /** One MySQL repeatable-read snapshot keeps configVersion, policy and variants consistent. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EvaluationResponse evaluate(EvaluateRequest request) {
        inputs.command(request);
        var config = control.getConfig(request.projectKey(), request.environmentKey(), request.flagKey());
        var variants = control.listVariants(request.projectKey(), request.flagKey());
        FlagVariant selected = variants.stream().filter(v -> Objects.equals(v.getId(), config.getDefaultVariantId()))
                .findFirst().orElseThrow(() -> BusinessException.notFound("Default variant"));
        Reason reason = config.isEnabled() ? Reason.DEFAULT : Reason.DISABLED;
        Integer priority = null;
        Integer bucket = null;
        String selectedKey = selected.getVariantKey();
        if (config.isEnabled() && config.getEvaluationPolicyJson() != null) {
            var policy = json.readValue(config.getEvaluationPolicyJson(), EvaluationPolicy.class);
            var matched = rules.firstMatch(policy.rules(), request.context());
            if (matched.isPresent()) {
                selectedKey = matched.get().variantKey();
                priority = matched.get().priority();
                reason = Reason.RULE_MATCH;
            } else if (policy.rollout() != null) {
                bucket = buckets.bucket(request.projectKey(), request.environmentKey(), request.flagKey(), request.context().userId());
                int upper = 0;
                for (var allocation : policy.rollout()) {
                    upper += allocation.weight();
                    if (bucket < upper) {
                        selectedKey = allocation.variantKey();
                        break;
                    }
                }
                reason = Reason.PERCENTAGE_ROLLOUT;
            }
        }
        String key = selectedKey;
        selected = variants.stream().filter(v -> v.getVariantKey().equals(key)).findFirst()
                .orElseThrow(() -> BusinessException.notFound("Policy variant"));
        return new EvaluationResponse(request.flagKey(), selectedKey, json.readTree(selected.getValueJson()),
                reason, config.getVersion(), priority, bucket);
    }
}
