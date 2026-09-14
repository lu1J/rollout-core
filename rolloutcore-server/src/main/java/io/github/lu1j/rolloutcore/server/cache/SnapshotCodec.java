package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SnapshotCodec {
    private final ObjectMapper json;
    public SnapshotCodec(ObjectMapper json) { this.json = json; }
    public String encode(EvaluationSnapshot s) {
        return json.writeValueAsString(new Document(1, s.key(), s.configVersion(), s.enabled(), s.defaultVariantKey(), s.policy(), s.variants()));
    }
    public EvaluationSnapshot decode(CacheKey expected, String payload) {
        var tree = json.readTree(payload);
        if (!tree.path("schemaVersion").isIntegralNumber() || tree.path("schemaVersion").asInt() != 1
                || !tree.path("configVersion").isIntegralNumber() || !tree.path("configVersion").canConvertToLong()
                || !tree.path("enabled").isBoolean() || !tree.path("variants").isObject())
            throw new IllegalArgumentException("Invalid snapshot envelope");
        CacheKey key = json.treeToValue(tree.required("key"), CacheKey.class);
        if (!key.equals(expected)) throw new IllegalArgumentException("Snapshot key mismatch");
        var variants = new LinkedHashMap<String, EvaluationSnapshot.VariantSnapshot>();
        tree.required("variants").properties().forEach(e -> {
            var v = e.getValue();
            variants.put(e.getKey(), new EvaluationSnapshot.VariantSnapshot(v.required("variantKey").asString(),
                    EvaluationSnapshot.immutableJson(v.required("value"))));
        });
        var policy = tree.required("policy");
        return new EvaluationSnapshot(key, tree.required("configVersion").asLong(), tree.required("enabled").asBoolean(),
                tree.required("defaultVariantKey").asString(), policy.isNull() ? null : json.treeToValue(policy, EvaluationPolicy.class), variants);
    }
    private record Document(int schemaVersion, CacheKey key, long configVersion, boolean enabled, String defaultVariantKey,
            EvaluationPolicy policy, Map<String, EvaluationSnapshot.VariantSnapshot> variants) {}
}
