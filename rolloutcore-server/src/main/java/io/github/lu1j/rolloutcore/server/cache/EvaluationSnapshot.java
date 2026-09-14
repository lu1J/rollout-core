package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy;
import io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicyValidator;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy.*;

/** No mutable entities or JSON nodes escape this snapshot. Policy access makes a defensive tree copy. */
public final class EvaluationSnapshot {
    private final CacheKey key;
    private final long configVersion;
    private final boolean enabled;
    private final String defaultVariantKey;
    private final EvaluationPolicy policy;
    private final Map<String, VariantSnapshot> variants;

    public EvaluationSnapshot(CacheKey key, long configVersion, boolean enabled, String defaultVariantKey,
            EvaluationPolicy policy, Map<String, VariantSnapshot> variants) {
        this.key = Objects.requireNonNull(key);
        if (configVersion < 0) throw new IllegalArgumentException("Negative config version");
        this.configVersion = configVersion;
        this.enabled = enabled;
        this.defaultVariantKey = Objects.requireNonNull(defaultVariantKey);
        this.variants = Map.copyOf(variants);
        if (!this.variants.containsKey(defaultVariantKey)) throw new IllegalArgumentException("Missing default variant");
        this.variants.forEach((name, variant) -> {
            io.github.lu1j.rolloutcore.server.service.InputRules.key(name);
            if (!name.equals(variant.variantKey())) throw new IllegalArgumentException("Variant key mismatch");
        });
        if (policy != null) new EvaluationPolicyValidator().validate(policy, this.variants.keySet());
        this.policy = copyPolicy(policy);
    }
    public CacheKey key() { return key; }
    public long configVersion() { return configVersion; }
    public boolean enabled() { return enabled; }
    public String defaultVariantKey() { return defaultVariantKey; }
    public EvaluationPolicy policy() { return copyPolicy(policy); }
    public Map<String, VariantSnapshot> variants() { return variants; }

    private static EvaluationPolicy copyPolicy(EvaluationPolicy p) {
        if (p == null) return null;
        return new EvaluationPolicy(p.rules() == null ? null : p.rules().stream().map(r -> new TargetingRule(
                r.priority(), r.match(), r.conditions().stream().map(c -> new RuleCondition(
                        c.attribute(), c.operator(), c.value().deepCopy())).toList(), r.variantKey())).toList(),
                p.rollout() == null ? null : List.copyOf(p.rollout()));
    }

    /** Immutable JSON-compatible graph: maps/lists, String, Boolean, BigDecimal and null. */
    public record VariantSnapshot(String variantKey, Object value) {
        public VariantSnapshot {
            Objects.requireNonNull(variantKey);
            Objects.requireNonNull(value);
            value = freeze(value);
        }
        private static Object freeze(Object value) {
            if (value == null || value instanceof String || value instanceof Boolean || value instanceof java.math.BigDecimal) return value;
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((k, v) -> result.put((String) k, freeze(v)));
                return Collections.unmodifiableMap(result);
            }
            if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(VariantSnapshot::freeze).toList());
            throw new IllegalArgumentException("Unsupported JSON value");
        }
    }
    public static Object immutableJson(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isTextual()) return node.asString();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.decimalValue();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(n -> values.add(immutableJson(n)));
            return Collections.unmodifiableList(values);
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.properties().forEach(e -> values.put(e.getKey(), immutableJson(e.getValue())));
            return Collections.unmodifiableMap(values);
        }
        throw new IllegalArgumentException("Unsupported JSON node");
    }
}
