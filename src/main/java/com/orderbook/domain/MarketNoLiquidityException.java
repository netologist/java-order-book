package com.orderbook.domain;

public class MarketNoLiquidityException extends RuntimeException {
    public MarketNoLiquidityException(String message) {
        super(message);
    }
}
