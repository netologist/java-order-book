package com.orderbook.api.dto;

import com.orderbook.domain.OrderType;
import com.orderbook.domain.Side;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PlaceOrderRequest(
        @NotBlank(message = "User ID is required") String userId,
        @NotNull(message = "Side is required") Side side,
        @NotNull(message = "OrderType is required") OrderType type,
        long price,
        @Positive(message = "Quantity must be positive") long quantity
) {}
