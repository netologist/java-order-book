package com.orderbook.api.dto;

import java.time.Instant;
import java.util.List;

public record TradeDto(
        String buyOrderId,
        String sellOrderId,
        long price,
        long quantity,
        Instant timestamp
) {}
