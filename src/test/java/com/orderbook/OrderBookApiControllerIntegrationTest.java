package com.orderbook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderbook.api.dto.PlaceOrderRequest;
import com.orderbook.api.OrderBookApiController;
import com.orderbook.domain.OrderType;
import com.orderbook.domain.Side;
import com.orderbook.market.MarketState;
import com.orderbook.service.OrderOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrderBookApiControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderOrchestratorService orchestrator;

    @BeforeEach
    void setUp() {
        if (orchestrator.getMarket().getState() == MarketState.OPEN) {
            orchestrator.getMarket().transition(MarketState.LIVE);
        }
    }

    @Test
    @DisplayName("End-to-End: Credit user, place limit order, check orderbook and depth")
    void testEndToEndOrderFlow() throws Exception {
        // 1. Credit buyer and seller
        mockMvc.perform(post("/api/v1/ledger/alice/credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderBookApiController.CreditRequest(100_000))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/ledger/bob/credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderBookApiController.CreditRequest(100_000))))
                .andExpect(status().isOk());

        // 2. Bob places resting Ask at 64¢ for 10 lots
        var bobAsk = new PlaceOrderRequest("bob", Side.SELL, OrderType.LIMIT, 64, 10);
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bobAsk)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("RESTING")));

        // 3. Alice places crossing Buy at 65¢ for 4 lots (Idempotency Key included)
        var aliceBuy = new PlaceOrderRequest("alice", Side.BUY, OrderType.LIMIT, 65, 4);
        String idempotencyKey = "idemp-alice-order-1";

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(aliceBuy)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("FILLED")))
                .andExpect(jsonPath("$.trades", hasSize(1)))
                .andExpect(jsonPath("$.trades[0].price", is(64))); // Price improvement: 64¢

        // 4. Duplicate POST with same Idempotency-Key returns cached response
        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(aliceBuy)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("FILLED")));

        // 5. Check OrderBook depth
        mockMvc.perform(get("/api/v1/orderbook/depth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asks.64", is(6))); // 10 - 4 = 6 remaining
    }
}
