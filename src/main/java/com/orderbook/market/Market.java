package com.orderbook.market;

import com.orderbook.domain.Order;
import com.orderbook.domain.OrderBookSnapshot;
import com.orderbook.domain.Trade;
import com.orderbook.engine.OrderBook;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Market orchestrates the OrderBook with a strict lifecycle state machine.
 * <p>
 * <b>State Gating Invariants:</b>
 * <ul>
 *   <li>{@link #placeOrder(Order)} is ONLY permitted when state is {@link MarketState#LIVE}.</li>
 *   <li>{@link #cancelOrder(String)} is permitted in {@link MarketState#OPEN}, {@link MarketState#LIVE},
 *       and {@link MarketState#PAUSED}, but strictly blocked in {@link MarketState#SETTLED}.</li>
 * </ul>
 */
public class Market {

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    private MarketState state = MarketState.OPEN;
    private final OrderBook book = new OrderBook();

    /**
     * Transitions market to a new state if permitted by the transition graph.
     */
    public void transition(MarketState to) {
        Objects.requireNonNull(to, "Target state cannot be null");
        writeLock.lock();
        try {
            if (!state.canTransitionTo(to)) {
                throw new InvalidStateTransitionException("Illegal state transition from " + state + " to " + to);
            }
            this.state = to;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Places an order. Gated strictly: market must be LIVE.
     */
    public OrderBook.MatchResult placeOrder(Order order) {
        readLock.lock();
        try {
            if (state != MarketState.LIVE) {
                throw new MarketNotLiveException("Market is not in LIVE state (current: " + state + ")");
            }
        } finally {
            readLock.unlock();
        }

        // Delegate to underlying book
        return book.placeOrder(order);
    }

    /**
     * Cancels an order. Gated: allowed in OPEN, LIVE, PAUSED; blocked in SETTLED.
     */
    public Order cancelOrder(String orderId) {
        readLock.lock();
        try {
            if (state == MarketState.SETTLED) {
                throw new IllegalStateException("Cannot cancel orders on a SETTLED market");
            }
        } finally {
            readLock.unlock();
        }

        return book.cancelOrder(orderId);
    }

    public MarketState getState() {
        readLock.lock();
        try {
            return state;
        } finally {
            readLock.unlock();
        }
    }

    public OrderBook getBook() {
        return book;
    }

    public OrderBookSnapshot toSnapshot() {
        return book.toSnapshot();
    }
}
