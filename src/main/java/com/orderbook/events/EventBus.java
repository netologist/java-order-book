package com.orderbook.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * High-Throughput Domain Event Bus utilizing Java 21 Virtual Threads.
 * <p>
 * Non-blocking dispatch: Observers execute concurrently on lightweight Virtual Threads,
 * guaranteeing that slow or blocking subscriber callbacks never stall the matching engine.
 */
public class EventBus implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<EventType, List<Consumer<Event>>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed = false;

    /**
     * Subscribes a consumer callback to a specific event type.
     */
    public void subscribe(EventType eventType, Consumer<Event> listener) {
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(listener);
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Dispatches an event to all subscribers asynchronously on Virtual Threads.
     */
    public void publish(Event event) {
        if (closed || event == null) return;

        List<Consumer<Event>> listeners = subscribers.get(event.type());
        if (listeners == null || listeners.isEmpty()) return;

        for (var listener : listeners) {
            virtualExecutor.submit(() -> {
                try {
                    listener.accept(event);
                } catch (Throwable t) {
                    log.error("Exception in EventBus listener for {}: {}", event.type(), t.getMessage());
                }
            });
        }
    }

    public int subscriberCount(EventType eventType) {
        List<Consumer<Event>> list = subscribers.get(eventType);
        return list == null ? 0 : list.size();
    }

    @Override
    public void close() {
        closed = true;
        virtualExecutor.close();
    }
}
