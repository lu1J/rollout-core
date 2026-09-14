package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.server.mapper.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "rolloutcore.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayService {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayService.class);
    private final OutboxEventMapper outbox;
    private final ConfigEventProducer producer;
    public OutboxRelayService(OutboxEventMapper outbox, ConfigEventProducer producer) {
        this.outbox = outbox; this.producer = producer;
    }

    // Small locked batch. A future network producer must bound its send timeout.
    @Scheduled(fixedDelayString = "${rolloutcore.outbox.poll-interval-ms:1000}",
               initialDelayString = "${rolloutcore.outbox.initial-delay-ms:1000}")
    @Transactional
    public void relayPending() {
        for (var event : outbox.findPending(20)) {
            try { producer.send(event); }
            catch (RuntimeException failure) {
                // Keep PENDING for the next poll; FAILED is reserved for a future terminal policy.
                LOG.warn("Outbox send failed; retaining PENDING event id={}", event.getEventId());
                continue;
            }
            if (outbox.markSent(event.getId(), LocalDateTime.now(ZoneOffset.UTC)) != 1)
                throw new IllegalStateException("Outbox SENT transition failed");
        }
    }
}
