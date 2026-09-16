package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.domain.*;
import java.time.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.support.SendResult;

final class KafkaEventTestSupport {
    static ConfigEventsProperties properties() {
        return new ConfigEventsProperties(ConfigEventsProperties.Transport.KAFKA, "instance-a",
                "rolloutcore.config-events", Duration.ofMillis(100), 2, Duration.ZERO, false);
    }
    static OutboxEvent row() {
        var row = new OutboxEvent();
        row.setId(42L); row.setEventId("12345678-1234-1234-1234-123456789abc");
        row.setEventType("CONFIG_CHANGED"); row.setProjectKey("shop"); row.setEnvironmentKey("prod");
        row.setFlagKey("pay"); row.setVersion(7); row.setStatus(OutboxStatus.PENDING);
        row.setCreatedAt(LocalDateTime.of(2026,9,16,0,0)); row.setUpdatedAt(row.getCreatedAt());
        row.setPayload("{\"key\":{\"projectKey\":\"shop\",\"environmentKey\":\"prod\",\"flagKey\":\"pay\"},\"latestVersion\":7}");
        return row;
    }
    static SendResult<String,String> ack() {
        return new SendResult<>(new ProducerRecord<>("rolloutcore.config-events","shop:prod:pay","{}"),
                new RecordMetadata(new TopicPartition("rolloutcore.config-events",0),8,0,0,0,0));
    }
}
