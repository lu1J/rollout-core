package io.github.lu1j.rolloutcore.server.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.*;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KafkaConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(OutboxConfiguration.class,KafkaEventsConfiguration.class)
            .withBean(io.micrometer.core.instrument.MeterRegistry.class, io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
            .withBean(ConfigChangedConsumer.class,() -> mock(ConfigChangedConsumer.class))
            .withPropertyValues("rolloutcore.events.listener-auto-startup=false");
    @Test void loggingDefaultDoesNotCreateKafkaAdapterOrSubscription() {
        runner.run(c -> {
            assertThat(c).hasSingleBean(ConfigEventProducer.class);
            assertThat(c.getBean(ConfigEventProducer.class)).isInstanceOf(LoggingProducer.class);
            assertThat(c).doesNotHaveBean(KafkaConfigChangedListener.class);
        });
    }
    @Test void explicitLoggingTransportWorksWithoutInstanceId() {
        runner.withPropertyValues("rolloutcore.events.transport=logging").run(c ->
                assertThat(c.getBean(ConfigEventProducer.class)).isInstanceOf(LoggingProducer.class));
    }
    @Test void kafkaRequiresStableInstanceId() {
        runner.withPropertyValues("rolloutcore.events.transport=kafka").run(c -> assertThat(c).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings={"", "bad id", "bad:id"})
    void rejectsInvalidInstanceId(String id) {
        runner.withPropertyValues("rolloutcore.events.transport=kafka","rolloutcore.events.instance-id="+id)
                .run(c -> assertThat(c).hasFailed());
    }
    @Test void unknownTransportFailsFast() {
        runner.withPropertyValues("rolloutcore.events.transport=unknown").run(c -> assertThat(c).hasFailed());
    }
    @Test void kafkaWiringUsesInstanceSpecificGroupAndRecordAck() {
        runner.withPropertyValues("rolloutcore.events.transport=kafka","rolloutcore.events.instance-id=instance-a",
                "rolloutcore.events.topic=custom.events","spring.kafka.bootstrap-servers=127.0.0.1:19092").run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(ConfigEventProducer.class).hasSingleBean(KafkaConfigChangedListener.class);
            assertThat(c.getBean(ConfigEventProducer.class)).isInstanceOf(KafkaConfigEventProducer.class);
            var props = c.getBean(ConfigEventsProperties.class);
            assertEquals("rolloutcore-cache-instance-a",props.groupId());
            var registry = c.getBean(KafkaListenerEndpointRegistry.class);
            var container = (AbstractMessageListenerContainer<?,?>)registry.getListenerContainer("rolloutcore-config-events");
            assertNotNull(container);
            assertFalse(container.isRunning());
            assertEquals(props.groupId(),container.getContainerProperties().getGroupId());
            assertEquals(ContainerProperties.AckMode.RECORD,container.getContainerProperties().getAckMode());
            assertTrue(container.getContainerProperties().isSyncCommits());
            assertInstanceOf(CommonContainerStoppingErrorHandler.class,container.getCommonErrorHandler());
            assertArrayEquals(new String[]{"custom.events"},container.getContainerProperties().getTopics());
            var config = c.getBean("configEventsConsumerFactory",ConsumerFactory.class).getConfigurationProperties();
            assertEquals(false,config.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
            assertEquals("earliest",config.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        });
    }
    @Test void differentInstancesHaveDifferentStableGroups() {
        var a = KafkaEventTestSupport.properties();
        var b = new ConfigEventsProperties(a.transport(),"instance-b",a.topic(),a.sendTimeout(),a.consumerRetries(),
                a.consumerRetryDelay(),false);
        assertEquals("rolloutcore-cache-instance-a",a.groupId());
        assertEquals("rolloutcore-cache-instance-b",b.groupId());
    }
    @Test void reliabilitySettingsOverrideUnsafeUserDefaults() {
        var kafka = new KafkaProperties();
        kafka.getProducer().getProperties().put("enable.idempotence","false");
        kafka.getConsumer().getProperties().put("enable.auto.commit","true");
        var config = KafkaEventsConfiguration.producerProperties(kafka,KafkaEventTestSupport.properties());
        assertEquals("all",config.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(true,config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        assertEquals(5,config.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
        assertEquals(Integer.MAX_VALUE,config.get(ProducerConfig.RETRIES_CONFIG));
        assertTrue((int)config.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG) >=
                (int)config.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)+(int)config.get(ProducerConfig.LINGER_MS_CONFIG));
        assertEquals(false,KafkaEventsConfiguration.consumerProperties(kafka,KafkaEventTestSupport.properties())
                .get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }
    @Test void terminalFailureStopsContainerAndDoesNotCommit() {
        var consumer = mock(Consumer.class);
        var container = mock(MessageListenerContainer.class);
        var handler = new CommonContainerStoppingErrorHandler(Runnable::run);
        assertThrows(org.springframework.kafka.KafkaException.class,() ->
                handler.handleRemaining(new IllegalStateException("failed"),java.util.List.of(),consumer,container));
        verify(container).stopAbnormally(any());
        verifyNoInteractions(consumer);
    }
    @ParameterizedTest @ValueSource(strings={"rolloutcore.events.consumer-retries=6","rolloutcore.events.consumer-retries=-1",
            "rolloutcore.events.send-timeout=1ms","rolloutcore.events.consumer-retry-delay=10s","rolloutcore.events.topic=bad topic"})
    void invalidBoundsFailBinding(String property) {
        runner.withPropertyValues(property).run(c -> assertThat(c).hasFailed());
    }
}
