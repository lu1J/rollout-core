package io.github.lu1j.rolloutcore.server.event;

import io.github.lu1j.rolloutcore.domain.OutboxEvent;

public interface ConfigEventProducer {
    /** Return only after acceptance; throw on failure. Retries may deliver duplicates. */
    void send(OutboxEvent event);
}
