package com.orderbook.api;

import com.orderbook.api.dto.PlaceOrderRequest;
import com.orderbook.api.dto.PlaceOrderResponse;
import com.orderbook.domain.Order;
import com.orderbook.domain.OrderBookSnapshot;
import com.orderbook.domain.Side;
import com.orderbook.ledger.UserBalance;
import com.orderbook.market.MarketState;
import com.orderbook.service.OrderOrchestratorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class OrderBookApiController {

    private final OrderOrchestratorService orchestrator;

    public OrderBookApiController(OrderOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    public record TransitionRequest(MarketState state) {}
    public record CreditRequest(@Positive long amountCents) {}

    // --- Order Endpoints ---

    @PostMapping("/orders")
    public ResponseEntity<PlaceOrderResponse> placeOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request
    ) {
        try {
            PlaceOrderResponse response = orchestrator.placeOrder(request, idempotencyKey);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        }
    }

    @DeleteMapping("/orders/{id}")
    public ResponseEntity<Order> cancelOrder(@PathVariable String id) {
        try {
            Order cancelled = orchestrator.cancelOrder(id);
            return ResponseEntity.ok(cancelled);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    // --- OrderBook & Depth Endpoints ---

    @GetMapping("/orderbook")
    public ResponseEntity<OrderBookSnapshot> getOrderBook() {
        return ResponseEntity.ok(orchestrator.getMarket().toSnapshot());
    }

    @GetMapping("/orderbook/depth")
    public ResponseEntity<Map<String, Map<Long, Long>>> getDepth() {
        var bidsDepth = orchestrator.getMarket().getBook().getDepth(Side.BUY);
        var asksDepth = orchestrator.getMarket().getBook().getDepth(Side.SELL);
        return ResponseEntity.ok(Map.of("bids", bidsDepth, "asks", asksDepth));
    }

    // --- Market Lifecycle Endpoints ---

    @GetMapping("/market/state")
    public ResponseEntity<Map<String, String>> getMarketState() {
        return ResponseEntity.ok(Map.of("state", orchestrator.getMarket().getState().name()));
    }

    @PostMapping("/market/transition")
    public ResponseEntity<Map<String, String>> transitionMarket(@RequestBody TransitionRequest request) {
        try {
            orchestrator.getMarket().transition(request.state());
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "state", orchestrator.getMarket().getState().name()));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    // --- Ledger Endpoints ---

    @PostMapping("/ledger/{userId}/credit")
    public ResponseEntity<Map<String, Object>> credit(
            @PathVariable String userId,
            @Valid @RequestBody CreditRequest req
    ) {
        orchestrator.getLedger().credit(userId, req.amountCents());
        UserBalance bal = orchestrator.getLedger().getBalance(userId).orElseThrow();
        return ResponseEntity.ok(Map.of("userId", userId, "balance", bal));
    }

    @GetMapping("/ledger/{userId}")
    public ResponseEntity<UserBalance> getBalance(@PathVariable String userId) {
        return orchestrator.getLedger().getBalance(userId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
