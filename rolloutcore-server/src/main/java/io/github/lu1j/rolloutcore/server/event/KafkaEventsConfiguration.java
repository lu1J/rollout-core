package io.github.lu1j.rolloutcore.server.event;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.*;

/** Boot-managed integration, activated only for the explicit Kafka transport. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rolloutcore.events.transport", havingValue = "kafka")
@EnableConfigurationProperties({KafkaProperties.class, ConfigEventsProperties.class})
@EnableKafka
public class KafkaEventsConfiguration {
    @Bean KafkaConfigEventCodec kafkaConfigEventCodec() { return new KafkaConfigEventCodec(); }

    static Map<String,Object> producerProperties(KafkaProperties kafka, ConfigEventsProperties events) {
        var config = new HashMap<>(kafka.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        int deadline = Math.toIntExact(events.sendTimeout().toMillis());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deadline);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.min(1000, deadline));
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (long) Math.min(1000, deadline));
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "rolloutcore-producer-" + events.instanceId());
        return config;
    }
    static Map<String,Object> consumerProperties(KafkaProperties kafka, ConfigEventsProperties events) {
        var config = new HashMap<>(kafka.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, events.groupId());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, events.groupId());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        return config;
    }
    @Bean ProducerFactory<String,String> configEventsProducerFactory(KafkaProperties kafka, ConfigEventsProperties events) {
        return new DefaultKafkaProducerFactory<>(producerProperties(kafka, events));
    }
    @Bean KafkaTemplate<String,String> configEventsKafkaTemplate(
            @Qualifier("configEventsProducerFactory") ProducerFactory<String,String> factory) {
        return new KafkaTemplate<>(factory);
    }
    @Bean
    @ConditionalOnMissingBean(ConfigEventProducer.class)
    ConfigEventProducer kafkaConfigEventProducer(@Qualifier("configEventsKafkaTemplate") KafkaTemplate<String,String> template,
            KafkaConfigEventCodec codec, ConfigEventsProperties events) {
        return new KafkaConfigEventProducer(template, codec, events);
    }
    @Bean ConsumerFactory<String,String> configEventsConsumerFactory(KafkaProperties kafka, ConfigEventsProperties events) {
        return new DefaultKafkaConsumerFactory<>(consumerProperties(kafka, events));
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String,String> configEventsListenerFactory(
            @Qualifier("configEventsConsumerFactory") ConsumerFactory<String,String> factory) {
        var listener = new ConcurrentKafkaListenerContainerFactory<String,String>();
        listener.setConsumerFactory(factory);
        listener.setConcurrency(1);
        listener.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        listener.getContainerProperties().setSyncCommits(true);
        // Adapter retries local processing finitely. Never skip an unhandled invalidation or commit its offset.
        listener.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return listener;
    }
    @Bean KafkaConfigChangedListener kafkaConfigChangedListener(KafkaConfigEventCodec codec,
            ConfigChangedConsumer consumer, ConfigEventsProperties events, io.micrometer.core.instrument.MeterRegistry registry) {
        return new KafkaConfigChangedListener(codec, consumer, events, registry);
    }
}
