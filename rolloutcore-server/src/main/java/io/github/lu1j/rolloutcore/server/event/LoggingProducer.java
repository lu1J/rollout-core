package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Development sink only: SENT means logged, not delivered to another process. */
public class LoggingProducer implements ConfigEventProducer {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingProducer.class);
    @Override public void send(OutboxEvent event) {
        LOG.info("Logging-only config event id={} version={}", event.getEventId(), event.getVersion());
    }
}
