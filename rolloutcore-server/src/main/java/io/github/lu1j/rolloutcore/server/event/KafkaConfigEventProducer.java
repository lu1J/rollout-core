package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.domain.OutboxEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

/** Return only after Broker acknowledgement. DB commit and Kafka acknowledgement are not atomic. */
public final class KafkaConfigEventProducer implements ConfigEventProducer {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConfigEventProducer.class);
    private final KafkaTemplate<String,String> template;
    private final KafkaConfigEventCodec codec;
    private final ConfigEventsProperties properties;
    public KafkaConfigEventProducer(KafkaTemplate<String,String> template, KafkaConfigEventCodec codec,
            ConfigEventsProperties properties) {
        this.template = template; this.codec = codec; this.properties = properties;
    }
    @Override public void send(OutboxEvent row) {
        var event = codec.fromOutbox(row);
        try {
            var result = template.send(properties.topic(), event.recordKey(), codec.encode(event))
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            var metadata = result.getRecordMetadata();
            LOG.info("Kafka config ack eventId={} key={} configVersion={} partition={} offset={}",
                    event.eventId(), event.recordKey(), event.configVersion(), metadata.partition(), metadata.offset());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka send interrupted; Outbox remains pending", ex);
        } catch (ExecutionException | TimeoutException ex) {
            // Timeout is ambiguous: Broker may still accept it. Relay retries the same eventId.
            throw new IllegalStateException("Kafka send not acknowledged; Outbox remains pending", ex);
        }
    }
}
