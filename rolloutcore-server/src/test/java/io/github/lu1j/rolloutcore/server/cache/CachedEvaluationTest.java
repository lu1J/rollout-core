package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.evaluation.*;
import io.github.lu1j.rolloutcore.server.service.InputRules;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationCommands.*;

class CachedEvaluationTest extends CacheFixture {
    @ParameterizedTest
    @CsvSource({"false,true,JP,DISABLED,old", "true,true,JP,RULE_MATCH,new", "true,true,US,PERCENTAGE_ROLLOUT,old", "true,false,US,DEFAULT,old"})
    void allEvaluationReasonsArePreservedOnCachedReads(boolean enabled, boolean withPolicy, String country, Reason reason, String variant) {
        var policy = json.readValue("{\"rules\":[{\"priority\":10,\"match\":\"ALL\",\"conditions\":[{\"attribute\":\"country\",\"operator\":\"EQ\",\"value\":\"JP\"}],\"variantKey\":\"new\"}],\"rollout\":[{\"variantKey\":\"new\",\"weight\":1000},{\"variantKey\":\"old\",\"weight\":9000}]}", EvaluationPolicy.class);
        var snapshot = new EvaluationSnapshot(key, 6, enabled, "old", withPolicy ? policy : null, snapshot(6, enabled).variants());
        var loads = new AtomicInteger(); var cache = cache(k -> { loads.incrementAndGet(); return snapshot; });
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            var service = new EvaluationService(cache, new InputRules(validator.getValidator()), new RuleEngine(), new StableBucketService(), json, metrics);
            var request = new EvaluateRequest("shop", "prod", "pay", new EvaluationContext("user-123", country, BigDecimal.valueOf(5), "2.3.1", null));
            var first = service.evaluate(request);
            assertEquals(reason, first.reason()); assertEquals(variant, first.variantKey()); assertEquals(6, first.configVersion());
            for (int i = 0; i < 5; i++) assertEquals(first, service.evaluate(request));
            assertEquals(1, loads.get());
            assertEquals(6, metrics.get("rolloutcore.evaluation").tag("outcome", reason.name().toLowerCase(java.util.Locale.ROOT)).timer().count());
            assertEquals(1, metrics.getMeters().size());
            assertEquals(java.util.List.of(io.micrometer.core.instrument.Tag.of("outcome", reason.name().toLowerCase(java.util.Locale.ROOT))), metrics.getMeters().getFirst().getId().getTags());
            if (reason == Reason.PERCENTAGE_ROLLOUT) assertEquals(2750, first.bucket());
            else assertNull(first.bucket());
            assertEquals(reason == Reason.RULE_MATCH ? 10 : null, first.matchedRulePriority());
        }
    }
    @Test void failedEvaluationRecordsTimerAndPreservesException() {
        var failure = new IllegalStateException("database unavailable");
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            var service = new EvaluationService(k -> { throw failure; }, new InputRules(validator.getValidator()),
                    new RuleEngine(), new StableBucketService(), json, metrics);
            var request = new EvaluateRequest("shop", "prod", "pay", new EvaluationContext("user-123", null, null, null, null));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.evaluate(request)));
            assertEquals(1, metrics.get("rolloutcore.evaluation").tag("outcome", "error").timer().count());
        }
    }
    @Test void cacheIsNotPerUserAndResponseMutationCannotAlterSnapshot() {
        var loads = new AtomicInteger();
        var snapshot = new EvaluationSnapshot(key, 6, true, "old", null, Map.of("old", new EvaluationSnapshot.VariantSnapshot(
                "old", EvaluationSnapshot.immutableJson(json.readTree("{\"a\":[1,true]}")))));
        var cache = cache(k -> { loads.incrementAndGet(); return snapshot; });
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            var service = new EvaluationService(cache, new InputRules(validator.getValidator()), new RuleEngine(), new StableBucketService(), json, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            var first = service.evaluate(new EvaluateRequest("shop", "prod", "pay", new EvaluationContext("user-a", null, null, null, null)));
            ((tools.jackson.databind.node.ObjectNode) first.value()).put("changed", true);
            var second = service.evaluate(new EvaluateRequest("shop", "prod", "pay", new EvaluationContext("user-b", null, null, null, null)));
            assertEquals(json.readTree("{\"a\":[1,true]}"), second.value()); assertEquals(1, loads.get());
        }
    }
}
