package io.github.lu1j.rolloutcore.sdk;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** SDK-owned HTTP context. Mutable JSON attributes are defensively copied. */
public record EvaluationContext(String userId, String country, BigDecimal vipLevel,
        String appVersion, Map<String, JsonNode> attributes) {
    public EvaluationContext { attributes = copy(attributes); }
    public EvaluationContext(String userId) { this(userId, null, null, null, Map.of()); }
    @Override public Map<String, JsonNode> attributes() { return copy(attributes); }
    private static Map<String, JsonNode> copy(Map<String, JsonNode> input) {
        if (input == null) return Map.of();
        var result = new LinkedHashMap<String, JsonNode>();
        input.forEach((key, value) -> result.put(key, value == null ? null : value.deepCopy()));
        return Collections.unmodifiableMap(result);
    }
}
