package io.github.lu1j.rolloutcore.openfeature;

import dev.openfeature.sdk.*;
import io.github.lu1j.rolloutcore.sdk.EvaluationResult;
import io.github.lu1j.rolloutcore.sdk.RolloutCoreClient;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Standard API adapter only. The injected SDK owns HTTP, retry and fallback; caller owns its lifecycle. */
public final class RolloutCoreProvider implements FeatureProvider {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final RolloutCoreClient client;
    private final String project;
    private final String environment;
    public RolloutCoreProvider(RolloutCoreClient client, String project, String environment) {
        this.client = Objects.requireNonNull(client);
        this.project = Objects.requireNonNull(project);
        this.environment = Objects.requireNonNull(environment);
    }
    @Override public Metadata getMetadata() { return () -> "RolloutCore"; }
    @Override public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean d, EvaluationContext c) {
        return resolve(c, d, ctx -> client.booleanFlag(project, environment, key, ctx, d), Function.identity());
    }
    @Override public ProviderEvaluation<String> getStringEvaluation(String key, String d, EvaluationContext c) {
        return resolve(c, d, ctx -> client.stringFlag(project, environment, key, ctx, d), Function.identity());
    }
    @Override public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer d, EvaluationContext c) {
        return resolve(c, d, ctx -> client.numberFlag(project, environment, key, ctx, BigDecimal.valueOf(d)),
                BigDecimal::intValueExact);
    }
    @Override public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double d, EvaluationContext c) {
        if (d == null || !Double.isFinite(d)) return error(d, ErrorCode.TYPE_MISMATCH, "Non-finite numeric default");
        return resolve(c, d, ctx -> client.numberFlag(project, environment, key, ctx, BigDecimal.valueOf(d)),
                number -> {
                    double value = number.doubleValue();
                    if (!Double.isFinite(value)) throw new ArithmeticException("Number exceeds double range");
                    return value;
                });
    }
    @Override public ProviderEvaluation<Value> getObjectEvaluation(String key, Value d, EvaluationContext c) {
        if (d == null || !(d.isStructure() || d.isList()))
            return error(d, ErrorCode.TYPE_MISMATCH, "JSON default must be an object or array");
        JsonNode defaultJson;
        try { defaultJson = toJson(d); }
        catch (IllegalArgumentException ex) { return error(d, ErrorCode.TYPE_MISMATCH, ex.getMessage()); }
        return resolve(c, d, ctx -> client.jsonFlag(project, environment, key, ctx, defaultJson), RolloutCoreProvider::fromJson);
    }
    private <S,T> ProviderEvaluation<T> resolve(EvaluationContext c, T d,
            Function<io.github.lu1j.rolloutcore.sdk.EvaluationContext, EvaluationResult<S>> evaluate,
            Function<S,T> convert) {
        if (c == null || c.getTargetingKey() == null || c.getTargetingKey().isBlank())
            return error(d, ErrorCode.TARGETING_KEY_MISSING, "targetingKey is required as userId");
        io.github.lu1j.rolloutcore.sdk.EvaluationContext context;
        try { context = context(c); }
        catch (IllegalArgumentException ex) { return error(d, ErrorCode.INVALID_CONTEXT, ex.getMessage()); }
        EvaluationResult<S> result = evaluate.apply(context);
        T value;
        try { value = result.source() == EvaluationResult.Source.DEFAULT ? d : convert.apply(result.value()); }
        catch (ArithmeticException ex) { return error(d, ErrorCode.TYPE_MISMATCH, ex.getMessage()); }
        var metadata = ImmutableMetadata.builder()
                .addString("rolloutcore.source", result.source().name())
                .addString("rolloutcore.reason", result.reason())
                .addString("rolloutcore.error", result.error().name())
                .addInteger("rolloutcore.attempts", result.attempts());
        if (result.configVersion() != null) metadata.addLong("rolloutcore.configVersion", result.configVersion());
        if (result.errorMessage() != null) metadata.addString("rolloutcore.errorMessage", result.errorMessage());
        // OpenFeature replaces any value with caller default when errorCode is set.
        // A successful LKG recovery therefore uses CACHED plus explicit failure metadata.
        boolean fallback = result.source() == EvaluationResult.Source.LKG;
        var builder = ProviderEvaluation.<T>builder().value(value).variant(result.variant())
                .reason(fallback ? "CACHED" : result.source() == EvaluationResult.Source.DEFAULT ? "ERROR" : reason(result.reason()))
                .flagMetadata(metadata.build());
        if (!fallback && result.error() != EvaluationResult.Error.NONE)
            builder.errorCode(errorCode(result.error())).errorMessage(result.errorMessage());
        return builder.build();
    }
    private static ErrorCode errorCode(EvaluationResult.Error error) {
        return switch (error) {
            case FLAG_NOT_FOUND -> ErrorCode.FLAG_NOT_FOUND;
            case TYPE_MISMATCH -> ErrorCode.TYPE_MISMATCH;
            case INVALID_CONTEXT -> ErrorCode.INVALID_CONTEXT;
            case PROTOCOL_ERROR -> ErrorCode.PARSE_ERROR;
            default -> ErrorCode.GENERAL;
        };
    }
    private static String reason(String reason) {
        return switch (reason) {
            case "RULE_MATCH" -> "TARGETING_MATCH";
            case "PERCENTAGE_ROLLOUT" -> "SPLIT";
            case "DISABLED", "DEFAULT" -> reason;
            default -> "UNKNOWN";
        };
    }
    private static <T> ProviderEvaluation<T> error(T value, ErrorCode code, String message) {
        return ProviderEvaluation.<T>builder().value(value).reason("ERROR").errorCode(code).errorMessage(message).build();
    }
    private static io.github.lu1j.rolloutcore.sdk.EvaluationContext context(EvaluationContext context) {
        Map<String, Value> values = new HashMap<>(context.asMap());
        values.remove(EvaluationContext.TARGETING_KEY);
        values.remove("userId"); // targetingKey is authoritative.
        String country = string(values.remove("country"), "country");
        String appVersion = string(values.remove("appVersion"), "appVersion");
        Value vip = values.remove("vipLevel");
        BigDecimal level = null;
        if (vip != null && !vip.isNull()) {
            if (!vip.isNumber() || !Double.isFinite(vip.asDouble()))
                throw new IllegalArgumentException("vipLevel must be numeric");
            level = BigDecimal.valueOf(vip.asDouble());
        }
        Map<String, JsonNode> attributes = new TreeMap<>();
        values.forEach((key, value) -> attributes.put(key, toJson(value)));
        return new io.github.lu1j.rolloutcore.sdk.EvaluationContext(
                context.getTargetingKey(), country, level, appVersion, attributes);
    }
    private static String string(Value value, String name) {
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException(name + " must be a string");
        return value.asString();
    }
    private static JsonNode toJson(Value value) {
        if (value == null || value.isNull()) return NODES.nullNode();
        if (value.isBoolean()) return NODES.booleanNode(value.asBoolean());
        if (value.isString()) return NODES.stringNode(value.asString());
        if (value.isNumber()) {
            if (!Double.isFinite(value.asDouble())) throw new IllegalArgumentException("Non-finite attribute");
            return NODES.numberNode(value.asDouble());
        }
        if (value.isList()) {
            var array = NODES.arrayNode();
            value.asList().forEach(item -> array.add(toJson(item)));
            return array;
        }
        if (value.isStructure()) {
            var object = NODES.objectNode();
            new TreeMap<>(value.asStructure().asMap()).forEach((key, item) -> object.set(key, toJson(item)));
            return object;
        }
        throw new IllegalArgumentException("Instant attributes are unsupported; provide an explicit string");
    }
    private static Value fromJson(JsonNode value) {
        if (value.isNull()) return new Value();
        if (value.isBoolean()) return new Value(value.asBoolean());
        if (value.isString()) return new Value(value.asString());
        if (value.isNumber()) {
            if (value.isIntegralNumber() && value.canConvertToInt()) return new Value(value.intValue());
            double number = value.doubleValue();
            if (!Double.isFinite(number)) throw new ArithmeticException("JSON number exceeds double range");
            return new Value(number);
        }
        if (value.isArray()) {
            List<Value> list = new ArrayList<>();
            value.forEach(item -> list.add(fromJson(item)));
            return new Value(list);
        }
        Map<String, Value> map = new LinkedHashMap<>();
        value.properties().forEach(entry -> map.put(entry.getKey(), fromJson(entry.getValue())));
        return new Value(new ImmutableStructure(map));
    }
}
