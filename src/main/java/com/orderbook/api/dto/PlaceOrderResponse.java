package com.orderbook.api.dto;

import java.util.List;

public record PlaceOrderResponse(
        String orderId,
        String status,
        long remainingQuantity,
        List<TradeDto> trades
) {}
