package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy;
import io.github.lu1j.rolloutcore.server.service.BusinessException;
import io.github.lu1j.rolloutcore.server.service.ControlPlaneService;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Objects;

@Repository
public class SnapshotRepository {
    private final ControlPlaneService control;
    private final ObjectMapper json;
    public SnapshotRepository(ControlPlaneService control, ObjectMapper json) { this.control = control; this.json = json; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EvaluationSnapshot load(CacheKey key) {
        var config = control.getConfig(key.projectKey(), key.environmentKey(), key.flagKey());
        var variants = control.listVariants(key.projectKey(), key.flagKey());
        var defaultVariant = variants.stream().filter(v -> Objects.equals(v.getId(), config.getDefaultVariantId()))
                .findFirst().orElseThrow(() -> BusinessException.notFound("Default variant"));
        var values = new LinkedHashMap<String, EvaluationSnapshot.VariantSnapshot>();
        variants.forEach(v -> values.put(v.getVariantKey(), new EvaluationSnapshot.VariantSnapshot(
                v.getVariantKey(), EvaluationSnapshot.immutableJson(json.readTree(v.getValueJson())))));
        var policy = config.getEvaluationPolicyJson() == null ? null
                : json.readValue(config.getEvaluationPolicyJson(), EvaluationPolicy.class);
        return new EvaluationSnapshot(key, config.getVersion(), config.isEnabled(), defaultVariant.getVariantKey(), policy, values);
    }
}
