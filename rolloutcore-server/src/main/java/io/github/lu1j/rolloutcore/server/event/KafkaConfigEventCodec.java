package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.domain.OutboxEvent;
import java.time.Instant;
import java.time.ZoneOffset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit schema validation avoids coercion/missing primitive defaults in untrusted Kafka JSON. */
public final class KafkaConfigEventCodec {
    private final JsonMapper json = JsonMapper.builder().build();
    public KafkaConfigChanged fromOutbox(OutboxEvent row) {
        if (!"CONFIG_CHANGED".equals(row.getEventType()) || row.getCreatedAt() == null)
            throw new IllegalArgumentException("Invalid config Outbox row");
        // createdAt is written by ControlPlaneService using UTC; retry never generates a new ID/time.
        return new KafkaConfigChanged(row.getEventId(), 1, row.getProjectKey(), row.getEnvironmentKey(),
                row.getFlagKey(), row.getVersion(), row.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
    public String encode(KafkaConfigChanged event) {
        var node = json.createObjectNode();
        node.put("eventId",event.eventId()).put("schemaVersion",event.schemaVersion())
                .put("projectKey",event.projectKey()).put("environmentKey",event.environmentKey())
                .put("flagKey",event.flagKey()).put("configVersion",event.configVersion())
                .put("occurredAt",event.occurredAt().toString());
        return json.writeValueAsString(node);
    }
    public KafkaConfigChanged decode(String payload) {
        if (payload == null || payload.length() > 8192) throw new IllegalArgumentException("Invalid event size");
        JsonNode node = json.readTree(payload);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Event must be an object");
        long schema = integer(node, "schemaVersion");
        if (schema != 1) throw new IllegalArgumentException("Unsupported event schemaVersion");
        return new KafkaConfigChanged(text(node,"eventId"), 1, text(node,"projectKey"),
                text(node,"environmentKey"), text(node,"flagKey"), integer(node,"configVersion"),
                Instant.parse(text(node,"occurredAt")));
    }
    private static String text(JsonNode node, String name) {
        if (!node.path(name).isString()) throw new IllegalArgumentException("Missing/invalid " + name);
        return node.path(name).asString();
    }
    private static long integer(JsonNode node, String name) {
        var value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException("Missing/invalid " + name);
        return value.longValue();
    }
}
