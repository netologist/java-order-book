package com.orderbook.domain;

/**
 * Direction of an order (Buy or Sell).
 */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
