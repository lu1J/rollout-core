package io.github.lu1j.rolloutcore.server.evaluation;

import io.github.lu1j.rolloutcore.server.cache.CacheKey;
import io.github.lu1j.rolloutcore.server.cache.SnapshotProvider;
import io.github.lu1j.rolloutcore.server.service.BusinessException;
import io.github.lu1j.rolloutcore.server.service.InputRules;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationCommands.*;

@Service
public class EvaluationService {
    private final SnapshotProvider snapshots;
    private final InputRules inputs;
    private final RuleEngine rules;
    private final StableBucketService buckets;
    private final ObjectMapper json;

    public EvaluationService(SnapshotProvider snapshots, InputRules inputs, RuleEngine rules,
            StableBucketService buckets, ObjectMapper json) {
        this.snapshots = snapshots;
        this.inputs = inputs;
        this.rules = rules;
        this.buckets = buckets;
        this.json = json;
    }

    /** Cache hits need no database transaction; the repository owns consistent miss reads. */
    public EvaluationResponse evaluate(EvaluateRequest request) {
        inputs.command(request);
        var config = snapshots.get(new CacheKey(request.projectKey(), request.environmentKey(), request.flagKey())).snapshot();
        if (config == null) throw BusinessException.notFound("Evaluation configuration");
        Reason reason = config.enabled() ? Reason.DEFAULT : Reason.DISABLED;
        Integer priority = null;
        Integer bucket = null;
        String selectedKey = config.defaultVariantKey();
        var policy = config.policy();
        if (config.enabled() && policy != null) {
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
        var selected = config.variants().get(selectedKey);
        // Rehydrate the response tree with the same numeric-node semantics as Day2's readTree(valueJson).
        return new EvaluationResponse(request.flagKey(), selectedKey, json.readTree(json.writeValueAsString(selected.value())),
                reason, config.configVersion(), priority, bucket);
    }
}
