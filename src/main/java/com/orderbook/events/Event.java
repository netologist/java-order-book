package com.orderbook.events;

import java.time.Instant;

/**
 * Immutable Domain Event Record.
 */
public record Event(
        EventType type,
        Object payload,
        Instant timestamp
) {
    public Event(EventType type, Object payload) {
        this(type, payload, Instant.now());
    }
}
