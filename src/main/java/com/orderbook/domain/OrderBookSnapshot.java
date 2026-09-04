package com.orderbook.domain;

import java.util.List;
import java.util.Optional;

/**
 * Immutable snapshot of the current OrderBook state.
 *
 * @param bids     List of resting bids (highest price first)
 * @param asks     List of resting asks (lowest price first)
 * @param bestBid  Top of book bid
 * @param bestAsk  Top of book ask
 * @param spread   Spread in cents (bestAsk.price - bestBid.price) or empty if missing liquidity
 */
public record OrderBookSnapshot(
        List<Order> bids,
        List<Order> asks,
        Optional<Order> bestBid,
        Optional<Order> bestAsk,
        Optional<Long> spread
) {
    public OrderBookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
