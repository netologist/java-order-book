package com.orderbook.domain;

/**
 * Selects the execution and matching strategy.
 */
public enum OrderType {
    /**
     * Matches at or better than the limit price; rests unfilled remainder on the book.
     */
    LIMIT,

    /**
     * Sweeps the opposite side at any available price. Never rests on the book.
     */
    MARKET,

    /**
     * Immediate-or-Cancel: fills as much as crosses within price limit; cancels any remainder.
     */
    IOC
}
