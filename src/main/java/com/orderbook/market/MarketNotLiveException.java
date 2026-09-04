package com.orderbook.market;

public class MarketNotLiveException extends RuntimeException {
    public MarketNotLiveException(String message) {
        super(message);
    }
}
