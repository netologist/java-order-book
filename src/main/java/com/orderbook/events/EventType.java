package com.orderbook.events;

/**
 * EventType categories for order book domain events.
 */
public enum EventType {
    ORDER_PLACED,
    ORDER_CANCELLED,
    TRADE_EXECUTED,
    MARKET_STATE_CHANGED
}
