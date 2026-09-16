package io.github.lu1j.rolloutcore.server.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.lu1j.rolloutcore.server.event.KafkaEventTestSupport.*;

class KafkaConfigEventCodecTest {
    final KafkaConfigEventCodec codec = new KafkaConfigEventCodec();
    @Test void roundTripPreservesStableOutboxIdAndUtcTime() {
        var event = codec.fromOutbox(row());
        assertEquals(row().getEventId(),event.eventId());
        assertEquals("2026-09-16T00:00:00Z",event.occurredAt().toString());
        assertEquals("shop:prod:pay",event.recordKey());
        assertEquals(event,codec.decode(codec.encode(event)));
        assertEquals(7,event.change().latestVersion());
        assertEquals(codec.encode(event),codec.encode(codec.fromOutbox(row())));
        assertFalse(codec.encode(event).contains("payload"));
    }
    @ParameterizedTest @ValueSource(strings={"eventId","schemaVersion","projectKey","environmentKey","flagKey","configVersion","occurredAt"})
    void everyProtocolFieldIsRequired(String field) {
        var node = JsonMapper.builder().build().readTree(codec.encode(codec.fromOutbox(row()))).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)node).remove(field);
        assertThrows(RuntimeException.class,() -> codec.decode(node.toString()));
    }
    @ParameterizedTest @ValueSource(strings={"{","[]","null","{}", "{\"schemaVersion\":2}", "{\"schemaVersion\":\"1\"}"})
    void rejectsMalformedOrUnsupportedEnvelopes(String json) {
        assertThrows(RuntimeException.class,() -> codec.decode(json));
    }
    @ParameterizedTest @ValueSource(strings={"-1","7.5","\"7\"","9223372036854775808","null"})
    void rejectsInvalidOrCoercedVersion(String value) {
        String json = codec.encode(codec.fromOutbox(row())).replace("\"configVersion\":7","\"configVersion\":"+value);
        assertThrows(RuntimeException.class,() -> codec.decode(json));
    }
    @Test void rejectsBadIdsKeysAndTimestamp() {
        var original = codec.encode(codec.fromOutbox(row()));
        for (String invalid : new String[]{
                original.replace(row().getEventId(),"not-uuid"),
                original.replace("\"shop\"","\"bad:key\""),
                original.replace("2026-09-16T00:00:00Z","2026-09-16T00:00:00")})
            assertThrows(RuntimeException.class,() -> codec.decode(invalid));
        assertThrows(IllegalArgumentException.class,() -> codec.decode(" ".repeat(8193)));
    }
    @Test void acceptsAdditiveFieldsAndVersionZero() {
        var r = row(); r.setVersion(0);
        String json = codec.encode(codec.fromOutbox(r)).replace("}",",\"future\":true}");
        assertEquals(0,codec.decode(json).configVersion());
    }
    @Test void outboxMustBeAConfigChangeWithReliableTimestamp() {
        var r = row(); r.setEventType("OTHER");
        assertThrows(IllegalArgumentException.class,() -> codec.fromOutbox(r));
        var missing = row(); missing.setCreatedAt(null);
        assertThrows(IllegalArgumentException.class,() -> codec.fromOutbox(missing));
    }
}
