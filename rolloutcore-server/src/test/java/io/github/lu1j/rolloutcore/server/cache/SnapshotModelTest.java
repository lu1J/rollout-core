package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ArrayNode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotModelTest extends CacheFixture {
    @Test void snapshotDefensivelyCopiesPolicyAndNestedVariantData() {
        var policy = json.readValue("{\"rules\":[{\"priority\":1,\"match\":\"ALL\",\"variantKey\":\"old\",\"conditions\":[{\"attribute\":\"country\",\"operator\":\"IN\",\"value\":[\"JP\"]}]}]}", EvaluationPolicy.class);
        Map<String, Object> mutable = new HashMap<>(); var nested = new ArrayList<Object>(); nested.add("before"); mutable.put("nested", nested);
        var variants = new HashMap<String, EvaluationSnapshot.VariantSnapshot>();
        variants.put("old", new EvaluationSnapshot.VariantSnapshot("old", mutable));
        var snapshot = new EvaluationSnapshot(key, 1, true, "old", policy, variants);
        nested.set(0, "after"); variants.clear();
        ((ArrayNode) policy.rules().getFirst().conditions().getFirst().value()).add("US");
        ((ArrayNode) snapshot.policy().rules().getFirst().conditions().getFirst().value()).add("FR");
        assertEquals(1, snapshot.policy().rules().getFirst().conditions().getFirst().value().size());
        assertEquals(Map.of("nested", List.of("before")), snapshot.variants().get("old").value());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.variants().clear());
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) snapshot.variants().get("old").value()).clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.policy().rules().clear());
    }
    @ParameterizedTest
    @ValueSource(strings = {"true", "\"text\"", "123.45", "{\"a\":[1,null,true]}", "[1,2]"})
    void redisJsonRoundTripsTypedValues(String value) {
        var s = new EvaluationSnapshot(key, 6, false, "old", null, Map.of("old",
                new EvaluationSnapshot.VariantSnapshot("old", EvaluationSnapshot.immutableJson(json.readTree(value)))));
        var decoded = codec.decode(key, codec.encode(s));
        assertEquals(s.variants(), decoded.variants()); assertEquals(6, decoded.configVersion()); assertFalse(decoded.enabled());
    }
    @Test void codecRejectsKeyMismatchAndUnknownSchema() {
        String encoded = codec.encode(snapshot(5, true));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new CacheKey("other", "prod", "pay"), encoded));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(key, encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
    }
    @Test void keyIsScopedAndUsesOnlyAllowedDelimiters() {
        assertEquals("rolloutcore:eval:v1:shop:prod:pay", key.redisKey());
        assertNotEquals(key, new CacheKey("shop", "test", "pay"));
        assertThrows(RuntimeException.class, () -> new CacheKey("shop:prod", "test", "pay"));
    }
    @Test void cacheConfigurationRejectsUnboundedOrMisorderedDefaults() {
        assertThrows(IllegalArgumentException.class, () -> new CacheProperties(false, properties.l1Ttl(), 0,
                properties.l2Ttl(), properties.negativeTtl(), properties.lkgTtl(), 10));
        assertThrows(IllegalArgumentException.class, () -> new CacheProperties(false, properties.l1Ttl(), 10,
                properties.l2Ttl(), properties.l1Ttl(), properties.lkgTtl(), 10));
    }
}
