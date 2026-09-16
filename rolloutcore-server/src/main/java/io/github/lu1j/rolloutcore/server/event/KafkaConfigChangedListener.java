package io.github.lu1j.rolloutcore.server.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Decode/validate/retry transport adapter; all cache semantics remain in ConfigChangedConsumer. */
public final class KafkaConfigChangedListener {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConfigChangedListener.class);
    private final KafkaConfigEventCodec codec;
    private final ConfigChangedConsumer consumer;
    private final ConfigEventsProperties properties;
    public KafkaConfigChangedListener(KafkaConfigEventCodec codec, ConfigChangedConsumer consumer,
            ConfigEventsProperties properties) {
        this.codec = codec; this.consumer = consumer; this.properties = properties;
    }
    @KafkaListener(id = "rolloutcore-config-events", topics = "${rolloutcore.events.topic:rolloutcore.config-events}",
            groupId = "rolloutcore-cache-${rolloutcore.events.instance-id}", containerFactory = "configEventsListenerFactory",
            autoStartup = "${rolloutcore.events.listener-auto-startup:true}")
    public void onMessage(ConsumerRecord<String,String> record) {
        KafkaConfigChanged event;
        try {
            event = codec.decode(record.value());
            if (!event.recordKey().equals(record.key())) throw new IllegalArgumentException("Kafka key/envelope mismatch");
        } catch (RuntimeException ex) {
            LOG.error("Invalid Kafka config event partition={} offset={}; stopping subscription without acknowledging",
                    record.partition(), record.offset());
            throw ex;
        }
        for (int attempt = 0; ; attempt++) {
            try {
                consumer.consume(event.change());
                LOG.info("Kafka config applied instance={} group={} eventId={} key={} configVersion={} partition={} offset={}",
                        properties.instanceId(), properties.groupId(), event.eventId(), event.recordKey(),
                        event.configVersion(), record.partition(), record.offset());
                return; // RECORD ack happens only after this successful return.
            } catch (RuntimeException ex) {
                if (attempt >= properties.consumerRetries()) {
                    LOG.error("Kafka config failed instance={} eventId={} key={} configVersion={}; stopping subscription",
                            properties.instanceId(), event.eventId(), event.recordKey(), event.configVersion(), ex);
                    throw ex;
                }
                try { Thread.sleep(properties.consumerRetryDelay()); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Kafka invalidation retry interrupted", interrupted);
                }
            }
        }
    }
}
