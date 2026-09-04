package com.orderbook.engine;

import com.orderbook.domain.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Thread-safe Limit Order Book and Matching Engine.
 * <p>
 * <b>Sorting Invariants (Price-Time Priority):</b>
 * <ul>
 *   <li><b>Bids:</b> Price DESC, Timestamp ASC, ID ASC (highest price first; FIFO at same price).</li>
 *   <li><b>Asks:</b> Price ASC, Timestamp ASC, ID ASC (lowest price first; FIFO at same price).</li>
 * </ul>
 * <b>Matching Rules:</b>
 * <ol>
 *   <li><b>Price Improvement:</b> Aggressor receives resting (passive) order's price.</li>
 *   <li><b>Self-Match Prevention:</b> An order never matches against an order from the same {@code userId}.</li>
 *   <li><b>Deterministic Execution:</b> Strict three-key sorting guarantees identical replay outcomes.</li>
 * </ol>
 */
public class OrderBook {

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    // 3-key comparators
    private static final Comparator<Order> BID_COMPARATOR = Comparator
            .comparingLong(Order::price).reversed()
            .thenComparing(Order::timestamp)
            .thenComparing(Order::id);

    private static final Comparator<Order> ASK_COMPARATOR = Comparator
            .comparingLong(Order::price)
            .thenComparing(Order::timestamp)
            .thenComparing(Order::id);

    private final List<Order> bids = new ArrayList<>();
    private final List<Order> asks = new ArrayList<>();

    public record MatchResult(List<Trade> trades, Optional<Order> restingOrder, long remainingQuantity) {}

    /**
     * Matches an incoming order against the book.
     *
     * @param order Incoming order
     * @return MatchResult containing executed trades and any resting/unfilled portions
     */
    public MatchResult placeOrder(Order order) {
        Objects.requireNonNull(order, "Order cannot be null");
        writeLock.lock();
        try {
            return switch (order.type()) {
                case LIMIT -> placeLimit(order);
                case MARKET -> placeMarket(order);
                case IOC -> placeIOC(order);
            };
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Cancels a resting order by ID.
     *
     * @param orderId ID of order to cancel
     * @return The cancelled Order
     * @throws OrderNotFoundException if order does not exist on either side of the book
     */
    public Order cancelOrder(String orderId) {
        Objects.requireNonNull(orderId, "Order ID cannot be null");
        writeLock.lock();
        try {
            Optional<Order> removed = removeById(bids, orderId);
            if (removed.isPresent()) {
                return removed.get();
            }
            removed = removeById(asks, orderId);
            if (removed.isPresent()) {
                return removed.get();
            }
            throw new OrderNotFoundException("Order '" + orderId + "' not found on book");
        } finally {
            writeLock.unlock();
        }
    }

    private MatchResult placeLimit(Order o) {
        // Self-match check at top of opposite book
        if (o.side() == Side.BUY) {
            if (!asks.isEmpty() && asks.get(0).userId().equals(o.userId()) && asks.get(0).price() <= o.price()) {
                // Prevent self-match: don't match, rest on book directly
                insertSorted(bids, o, BID_COMPARATOR);
                return new MatchResult(List.of(), Optional.of(o), o.quantity());
            }
            return matchBuy(o, false);
        } else {
            if (!bids.isEmpty() && bids.get(0).userId().equals(o.userId()) && bids.get(0).price() >= o.price()) {
                // Prevent self-match: rest on book
                insertSorted(asks, o, ASK_COMPARATOR);
                return new MatchResult(List.of(), Optional.of(o), o.quantity());
            }
            return matchSell(o, false);
        }
    }

    private MatchResult placeIOC(Order o) {
        // IOC fills whatever crosses within limit price, remainder is canceled/discarded
        return (o.side() == Side.BUY) ? matchBuy(o, true) : matchSell(o, true);
    }

    private MatchResult matchBuy(Order o, boolean isIOC) {
        List<Trade> trades = new ArrayList<>();
        long remaining = o.quantity();

        while (remaining > 0 && !asks.isEmpty() && asks.get(0).price() <= o.price()) {
            Order bestAsk = asks.get(0);

            // Self-match prevention
            if (bestAsk.userId().equals(o.userId())) {
                break; // Stop matching to avoid trading with oneself
            }

            long matchQty = Math.min(remaining, bestAsk.quantity());
            // Price improvement: trade executes at passive bestAsk.price()!
            trades.add(new Trade(o.id(), bestAsk.id(), bestAsk.price(), matchQty, Instant.now()));

            remaining -= matchQty;

            if (bestAsk.quantity() == matchQty) {
                asks.remove(0);
            } else {
                asks.set(0, bestAsk.withQuantity(bestAsk.quantity() - matchQty));
            }
        }

        Optional<Order> resting = Optional.empty();
        if (remaining > 0 && !isIOC) {
            Order restOrder = o.withQuantity(remaining);
            insertSorted(bids, restOrder, BID_COMPARATOR);
            resting = Optional.of(restOrder);
        }

        return new MatchResult(List.copyOf(trades), resting, remaining);
    }

    private MatchResult matchSell(Order o, boolean isIOC) {
        List<Trade> trades = new ArrayList<>();
        long remaining = o.quantity();

        while (remaining > 0 && !bids.isEmpty() && bids.get(0).price() >= o.price()) {
            Order bestBid = bids.get(0);

            // Self-match prevention
            if (bestBid.userId().equals(o.userId())) {
                break;
            }

            long matchQty = Math.min(remaining, bestBid.quantity());
            // Price improvement: trade executes at passive bestBid.price()!
            trades.add(new Trade(bestBid.id(), o.id(), bestBid.price(), matchQty, Instant.now()));

            remaining -= matchQty;

            if (bestBid.quantity() == matchQty) {
                bids.remove(0);
            } else {
                bids.set(0, bestBid.withQuantity(bestBid.quantity() - matchQty));
            }
        }

        Optional<Order> resting = Optional.empty();
        if (remaining > 0 && !isIOC) {
            Order restOrder = o.withQuantity(remaining);
            insertSorted(asks, restOrder, ASK_COMPARATOR);
            resting = Optional.of(restOrder);
        }

        return new MatchResult(List.copyOf(trades), resting, remaining);
    }

    private MatchResult placeMarket(Order o) {
        List<Trade> trades = new ArrayList<>();
        long remaining = o.quantity();
        List<Order> skipped = new ArrayList<>();
        List<Order> oppositeSide = (o.side() == Side.BUY) ? asks : bids;

        while (remaining > 0 && !oppositeSide.isEmpty()) {
            Order best = oppositeSide.remove(0);

            // Self-match prevention: skip same user's order
            if (best.userId().equals(o.userId())) {
                skipped.add(best);
                continue;
            }

            long matchQty = Math.min(remaining, best.quantity());
            Trade trade = (o.side() == Side.BUY)
                    ? new Trade(o.id(), best.id(), best.price(), matchQty, Instant.now())
                    : new Trade(best.id(), o.id(), best.price(), matchQty, Instant.now());

            trades.add(trade);
            remaining -= matchQty;

            if (best.quantity() > matchQty) {
                // Re-insert unfilled portion of resting order at front
                oppositeSide.add(0, best.withQuantity(best.quantity() - matchQty));
            }
        }

        // Re-insert any skipped same-user orders preserving order
        for (var s : skipped) {
            insertSorted(oppositeSide, s, (o.side() == Side.BUY) ? ASK_COMPARATOR : BID_COMPARATOR);
        }

        if (remaining > 0) {
            // Market orders cannot rest; fail if liquidity is exhausted
            throw new MarketNoLiquidityException("Market order found insufficient liquidity for " + remaining + " lots");
        }

        return new MatchResult(List.copyOf(trades), Optional.empty(), 0);
    }

    private static void insertSorted(List<Order> list, Order order, Comparator<Order> comp) {
        int index = Collections.binarySearch(list, order, comp);
        if (index < 0) {
            index = -(index + 1);
        }
        list.add(index, order);
    }

    private static Optional<Order> removeById(List<Order> list, String orderId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(orderId)) {
                return Optional.of(list.remove(i));
            }
        }
        return Optional.empty();
    }

    public Optional<Order> getBestBid() {
        readLock.lock();
        try {
            return bids.isEmpty() ? Optional.empty() : Optional.of(bids.get(0));
        } finally {
            readLock.unlock();
        }
    }

    public Optional<Order> getBestAsk() {
        readLock.lock();
        try {
            return asks.isEmpty() ? Optional.empty() : Optional.of(asks.get(0));
        } finally {
            readLock.unlock();
        }
    }

    public int bidCount() {
        readLock.lock();
        try { return bids.size(); } finally { readLock.unlock(); }
    }

    public int askCount() {
        readLock.lock();
        try { return asks.size(); } finally { readLock.unlock(); }
    }

    /**
     * Computes price depth aggregation using Java Streams.
     */
    public Map<Long, Long> getDepth(Side side) {
        readLock.lock();
        try {
            List<Order> list = (side == Side.BUY) ? bids : asks;
            return list.stream()
                    .collect(Collectors.groupingBy(
                            Order::price,
                            (side == Side.BUY) ? () -> new TreeMap<>(Comparator.reverseOrder()) : TreeMap::new,
                            Collectors.summingLong(Order::quantity)
                    ));
        } finally {
            readLock.unlock();
        }
    }

    public OrderBookSnapshot toSnapshot() {
        readLock.lock();
        try {
            Optional<Order> bestBid = bids.isEmpty() ? Optional.empty() : Optional.of(bids.get(0));
            Optional<Order> bestAsk = asks.isEmpty() ? Optional.empty() : Optional.of(asks.get(0));
            Optional<Long> spread = (bestBid.isPresent() && bestAsk.isPresent())
                    ? Optional.of(bestAsk.get().price() - bestBid.get().price())
                    : Optional.empty();

            return new OrderBookSnapshot(bids, asks, bestBid, bestAsk, spread);
        } finally {
            readLock.unlock();
        }
    }
}
