package com.orderbook.domain;

import java.time.Instant;

/**
 * Execution trade resulting from matching an incoming order against a resting order.
 * <p>
 * Trade price is ALWAYS the <b>passive (resting)</b> order's price.
 * This satisfies the <b>price improvement</b> convention:
 * an aggressor buying at 65¢ crossing a resting ask at 64¢ executes at 64¢.
 *
 * @param buyOrderId  Buy order ID
 * @param sellOrderId Sell order ID
 * @param price       Execution price in cents
 * @param quantity    Matched lots
 * @param timestamp   Execution timestamp
 */
public record Trade(
        String buyOrderId,
        String sellOrderId,
        long price,
        long quantity,
        Instant timestamp
) {}
