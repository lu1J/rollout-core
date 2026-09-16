package io.github.lu1j.rolloutcore.sdk;

import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

public interface RolloutCoreClient extends AutoCloseable {
    enum ValueType { BOOLEAN, STRING, NUMBER, JSON }
    EvaluationResult<JsonNode> evaluate(String project, String environment, String flag,
            EvaluationContext context, ValueType type, JsonNode defaultValue);
    EvaluationResult<Boolean> booleanFlag(String project, String environment, String flag,
            EvaluationContext context, boolean defaultValue);
    EvaluationResult<String> stringFlag(String project, String environment, String flag,
            EvaluationContext context, String defaultValue);
    EvaluationResult<BigDecimal> numberFlag(String project, String environment, String flag,
            EvaluationContext context, BigDecimal defaultValue);
    EvaluationResult<JsonNode> jsonFlag(String project, String environment, String flag,
            EvaluationContext context, JsonNode defaultValue);
    @Override default void close() {}
    static RolloutCoreClient create(ClientOptions options) { return new HttpRolloutCoreClient(options); }
}
