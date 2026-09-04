package com.orderbook.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable Order Entity modeled as a Java Record.
 * <p>
 * In prediction markets:
 * <ul>
 *   <li>Prices are integer cents in {@code [1, 100]} representing probability (e.g. 64¢ = 64%).</li>
 *   <li>For {@link OrderType#MARKET}, price is {@code 0} (sentinel indicating no price limit).</li>
 *   <li>All arithmetic uses {@code long} integer cents (never {@code float/double}).</li>
 * </ul>
 *
 * @param id        Unique order identifier
 * @param userId    Trader user identifier
 * @param side      {@link Side#BUY} or {@link Side#SELL}
 * @param type      {@link OrderType} (LIMIT, MARKET, IOC)
 * @param price     Price in integer cents ([1, 100] for LIMIT/IOC, 0 for MARKET)
 * @param quantity  Lots quantity (> 0)
 * @param timestamp Nanosecond-precision timestamp for price-time priority
 */
public record Order(
        String id,
        String userId,
        Side side,
        OrderType type,
        long price,
        long quantity,
        Instant timestamp
) {
    public Order {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Order ID cannot be empty");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("User ID cannot be empty");
        Objects.requireNonNull(side, "Side cannot be null");
        Objects.requireNonNull(type, "OrderType cannot be null");
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be strictly positive: " + quantity);

        if (type == OrderType.MARKET) {
            if (price != 0) {
                throw new IllegalArgumentException("Market order price must be 0 (received: " + price + ")");
            }
        } else {
            if (price < 1 || price > 100) {
                throw new IllegalArgumentException("Prediction market price must be in [1, 100] cents (received: " + price + ")");
            }
        }

        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public Order withQuantity(long newQuantity) {
        return new Order(id, userId, side, type, price, newQuantity, timestamp);
    }
}
