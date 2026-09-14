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
    void allDay2ReasonsArePreservedOnCachedReads(boolean enabled, boolean withPolicy, String country, Reason reason, String variant) {
        var policy = json.readValue("{\"rules\":[{\"priority\":10,\"match\":\"ALL\",\"conditions\":[{\"attribute\":\"country\",\"operator\":\"EQ\",\"value\":\"JP\"}],\"variantKey\":\"new\"}],\"rollout\":[{\"variantKey\":\"new\",\"weight\":1000},{\"variantKey\":\"old\",\"weight\":9000}]}", EvaluationPolicy.class);
        var snapshot = new EvaluationSnapshot(key, 6, enabled, "old", withPolicy ? policy : null, snapshot(6, enabled).variants());
        var loads = new AtomicInteger(); var cache = cache(k -> { loads.incrementAndGet(); return snapshot; });
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            var service = new EvaluationService(cache, new InputRules(validator.getValidator()), new RuleEngine(), new StableBucketService(), json);
            var request = new EvaluateRequest("shop", "prod", "pay", new EvaluationContext("user-123", country, BigDecimal.valueOf(5), "2.3.1", null));
            var first = service.evaluate(request);
            assertEquals(reason, first.reason()); assertEquals(variant, first.variantKey()); assertEquals(6, first.configVersion());
            for (int i = 0; i < 5; i++) assertEquals(first, service.evaluate(request));
            assertEquals(1, loads.get());
            if (reason == Reason.PERCENTAGE_ROLLOUT) assertEquals(2750, first.bucket());
            else assertNull(first.bucket());
            assertEquals(reason == Reason.RULE_MATCH ? 10 : null, first.matchedRulePriority());
        }
    }
    @Test void cacheIsNotPerUserAndResponseMutationCannotAlterSnapshot() {
        var loads = new AtomicInteger();
        var snapshot = new EvaluationSnapshot(key, 6, true, "old", null, Map.of("old", new EvaluationSnapshot.VariantSnapshot(
                "old", EvaluationSnapshot.immutableJson(json.readTree("{\"a\":[1,true]}")))));
        var cache = cache(k -> { loads.incrementAndGet(); return snapshot; });
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            var service = new EvaluationService(cache, new InputRules(validator.getValidator()), new RuleEngine(), new StableBucketService(), json);
            var first = service.evaluate(new EvaluateRequest("shop", "prod", "pay", new EvaluationContext("user-a", null, null, null, null)));
            ((tools.jackson.databind.node.ObjectNode) first.value()).put("changed", true);
            var second = service.evaluate(new EvaluateRequest("shop", "prod", "pay", new EvaluationContext("user-b", null, null, null, null)));
            assertEquals(json.readTree("{\"a\":[1,true]}"), second.value()); assertEquals(1, loads.get());
        }
    }
}
