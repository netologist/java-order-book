package com.orderbook.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe Idempotency Guard with TTL expiration.
 * Stores prior HTTP API responses by Idempotency-Key.
 */
public class IdempotencyGuard {

    private record Entry(Object response, Instant expiresAt) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> Optional<T> lookup(String key) {
        if (key == null || key.isBlank()) return Optional.empty();

        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }

        if (Instant.now().isAfter(entry.expiresAt())) {
            store.remove(key);
            return Optional.empty();
        }

        return Optional.of((T) entry.response());
    }

    public void store(String key, Object response, Duration ttl) {
        if (key == null || key.isBlank() || response == null) return;
        store.put(key, new Entry(response, Instant.now().plus(ttl)));
    }

    public int sweep() {
        Instant now = Instant.now();
        int initial = store.size();
        store.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
        return initial - store.size();
    }

    public int size() {
        return store.size();
    }
}
