package io.github.lu1j.rolloutcore.sdk;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Function;
import java.util.concurrent.*;
import javax.net.ssl.SSLException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import static io.github.lu1j.rolloutcore.sdk.EvaluationResult.Source.*;
import io.github.lu1j.rolloutcore.sdk.EvaluationResult.Error;

/** Thread-safe synchronous HTTP client. LKG is never consulted on healthy reads. */
public final class HttpRolloutCoreClient implements RolloutCoreClient {
    private final ClientOptions options;
    private final HttpClient http;
    private final URI endpoint;
    private final LongSupplier ticker;
    private final JsonMapper json = JsonMapper.builder()
            .enable(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final LinkedHashMap<CacheKey, Cached> lkg = new LinkedHashMap<>(16, .75f, true);
    // Fixed-size generations suppress late fills after observed business/protocol failures.
    private final long[] generations = new long[64];
    private record Cached(EvaluationResult<JsonNode> result, long time) {}
    private record CacheKey(String scope, String body, ValueType type) {}
    private record Request(String projectKey, String environmentKey, String flagKey, EvaluationContext context) {}

    public HttpRolloutCoreClient(ClientOptions options) {
        this(options, HttpClient.newBuilder().connectTimeout(options.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build(), System::nanoTime);
    }
    // Deterministic transport/monotonic-clock seam, package-private; no server dependency.
    HttpRolloutCoreClient(ClientOptions options, HttpClient http, LongSupplier ticker) {
        this.options = Objects.requireNonNull(options);
        this.http = Objects.requireNonNull(http);
        this.ticker = Objects.requireNonNull(ticker);
        endpoint = URI.create(options.baseUrl().toString().replaceAll("/+$", "") + "/api/v1/evaluate");
    }
    @Override public EvaluationResult<JsonNode> evaluate(String project, String environment, String flag,
            EvaluationContext context, ValueType type, JsonNode defaultValue) {
        Objects.requireNonNull(type);
        if (!matches(type, defaultValue)) throw new IllegalArgumentException("Default must match requested type");
        if (!key(project) || !key(environment) || !key(flag) || context == null
                || context.userId() == null || context.userId().isBlank()
                || context.userId().length() > 256 || context.userId().indexOf(0) >= 0) {
            return failure(defaultValue, Error.INVALID_CONTEXT, "Invalid key or userId", 0);
        }
        String body = json.writeValueAsString(new Request(project, environment, flag, context));
        // Full serialized context + requested type: no cross-user/attribute/type fallback.
        String scope = project + "/" + environment + "/" + flag;
        CacheKey cacheKey = new CacheKey(scope, body, type);
        int stripe = (scope.hashCode() & Integer.MAX_VALUE) % generations.length;
        long generation;
        synchronized (lkg) { generation = generations[stripe]; }
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(options.requestTimeout())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        Error error = Error.CONNECTION;
        String message = "Transport failed";
        int attempts = 0;
        for (; attempts <= options.maxRetries();) {
            attempts++;
            try {
                var response = send(request);
                int status = response.statusCode();
                if (status == 200) {
                    EvaluationResult<JsonNode> result;
                    try {
                        JsonNode node = json.readTree(response.body());
                        if (node == null || !node.isObject() || !flag.equals(node.path("flagKey").asString())
                                || !node.path("variantKey").isString() || node.path("variantKey").asString().isBlank()
                                || !node.path("reason").isString() || !node.path("configVersion").isIntegralNumber()
                                || !node.path("configVersion").canConvertToLong() || node.path("configVersion").asLong() < 0
                                || node.get("value") == null || node.get("value").isNull())
                            throw new IllegalArgumentException("Invalid evaluation response");
                        if (!matches(type, node.get("value"))) {
                            invalidate(cacheKey, stripe);
                            return failure(defaultValue, Error.TYPE_MISMATCH, "Flag value does not match " + type, attempts);
                        }
                        result = new EvaluationResult<>(node.get("value"), REMOTE, Error.NONE, null,
                                node.get("variantKey").asString(), node.get("reason").asString(),
                                node.get("configVersion").asLong(), attempts);
                    } catch (RuntimeException ex) {
                        invalidate(cacheKey, stripe);
                        return failure(defaultValue, Error.PROTOCOL_ERROR, "Invalid evaluation response", attempts);
                    }
                    synchronized (lkg) {
                        if (options.lkgCapacity() > 0 && generation == generations[stripe]) {
                            var previous = lkg.get(cacheKey);
                            if (previous == null || previous.result.configVersion() <= result.configVersion())
                                lkg.put(cacheKey, new Cached(result, ticker.getAsLong()));
                            while (lkg.size() > options.lkgCapacity()) lkg.remove(lkg.keySet().iterator().next());
                        }
                    }
                    return result;
                }
                if (status == 502 || status == 503 || status == 504) {
                    error = Error.SERVER_UNAVAILABLE; message = "Evaluation HTTP " + status;
                } else {
                    invalidate(cacheKey, stripe);
                    Error code = status == 404 ? Error.FLAG_NOT_FOUND
                            : status == 400 ? Error.INVALID_CONTEXT
                            : status >= 400 && status < 500 ? Error.BUSINESS_ERROR : Error.PROTOCOL_ERROR;
                    return failure(defaultValue, code, "Evaluation HTTP " + status, attempts);
                }
            } catch (HttpTimeoutException ex) {
                error = Error.TIMEOUT; message = "Evaluation request timed out";
            } catch (ConnectException ex) {
                error = Error.CONNECTION; message = "Evaluation connection failed";
            } catch (SSLException | ProtocolException ex) {
                invalidate(cacheKey, stripe);
                return failure(defaultValue, Error.PROTOCOL_ERROR, "TLS or HTTP protocol failure", attempts);
            } catch (IOException ex) {
                error = Error.CONNECTION; message = "Evaluation transport failed";
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return failure(defaultValue, Error.INTERRUPTED, "Evaluation interrupted", attempts);
            }
            if (attempts <= options.maxRetries()) {
                try { Thread.sleep(options.retryDelay()); }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return failure(defaultValue, Error.INTERRUPTED, "Retry interrupted", attempts);
                }
            }
        }
        synchronized (lkg) {
            Cached cached = lkg.get(cacheKey);
            if (cached != null) {
                if (ticker.getAsLong() - cached.time < options.lkgTtl().toNanos()) {
                    var good = cached.result;
                    return new EvaluationResult<>(good.value(), LKG, error, message,
                            good.variant(), good.reason(), good.configVersion(), attempts);
                }
                lkg.remove(cacheKey);
            }
        }
        return failure(defaultValue, error, message, attempts);
    }
    /** Include response-body consumption in the deadline, and cancel the exchange on timeout/interruption. */
    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(options.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new HttpTimeoutException("Evaluation deadline exceeded");
        } catch (InterruptedException ex) {
            future.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException interrupted) throw interrupted;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("HTTP exchange failed", cause);
        }
    }
    private void invalidate(CacheKey key, int stripe) {
        synchronized (lkg) {
            generations[stripe]++;
            lkg.keySet().removeIf(cached -> cached.scope().equals(key.scope()));
        }
    }
    private static boolean key(String value) { return value != null && value.matches("[a-z0-9._-]{1,100}"); }
    private static boolean matches(ValueType type, JsonNode value) {
        if (value == null) return false;
        return switch (type) {
            case BOOLEAN -> value.isBoolean();
            case STRING -> value.isString();
            case NUMBER -> value.isNumber();
            case JSON -> value.isObject() || value.isArray();
        };
    }
    private static EvaluationResult<JsonNode> failure(JsonNode value, Error error, String message, int attempts) {
        return new EvaluationResult<>(value, DEFAULT, error, message, null, "ERROR", null, attempts);
    }
    private static <T> EvaluationResult<T> map(EvaluationResult<JsonNode> r, Function<JsonNode, T> convert) {
        return new EvaluationResult<>(convert.apply(r.value()), r.source(), r.error(), r.errorMessage(),
                r.variant(), r.reason(), r.configVersion(), r.attempts());
    }
    @Override public EvaluationResult<Boolean> booleanFlag(String p, String e, String f, EvaluationContext c, boolean d) {
        return map(evaluate(p,e,f,c,ValueType.BOOLEAN,JsonNodeFactory.instance.booleanNode(d)), JsonNode::asBoolean);
    }
    @Override public EvaluationResult<String> stringFlag(String p, String e, String f, EvaluationContext c, String d) {
        return map(evaluate(p,e,f,c,ValueType.STRING,JsonNodeFactory.instance.stringNode(Objects.requireNonNull(d))), JsonNode::asString);
    }
    @Override public EvaluationResult<BigDecimal> numberFlag(String p, String e, String f, EvaluationContext c, BigDecimal d) {
        return map(evaluate(p,e,f,c,ValueType.NUMBER,JsonNodeFactory.instance.numberNode(Objects.requireNonNull(d))), JsonNode::decimalValue);
    }
    @Override public EvaluationResult<JsonNode> jsonFlag(String p, String e, String f, EvaluationContext c, JsonNode d) {
        return evaluate(p,e,f,c,ValueType.JSON,d);
    }
    @Override public void close() {
        http.shutdownNow();
        synchronized (lkg) { lkg.clear(); }
    }
}
