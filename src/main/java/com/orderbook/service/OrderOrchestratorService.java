package com.orderbook.service;

import com.orderbook.api.dto.PlaceOrderRequest;
import com.orderbook.api.dto.PlaceOrderResponse;
import com.orderbook.api.dto.TradeDto;
import com.orderbook.domain.*;
import com.orderbook.engine.OrderBook;
import com.orderbook.events.Event;
import com.orderbook.events.EventBus;
import com.orderbook.events.EventType;
import com.orderbook.idempotency.IdempotencyGuard;
import com.orderbook.ledger.Ledger;
import com.orderbook.market.Market;
import com.orderbook.market.MarketState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates the full lifecycle of an order:
 * Idempotency Check → Ledger Reserve → Market Gate → Matching Engine → Settle / Refund → Event Publish.
 */
@Service
public class OrderOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrderOrchestratorService.class);

    private final Market market = new Market();
    private final Ledger ledger = new Ledger();
    private final EventBus eventBus = new EventBus();
    private final IdempotencyGuard idempotencyGuard = new IdempotencyGuard();

    public PlaceOrderResponse placeOrder(PlaceOrderRequest req, String idempotencyKey) {
        // 1. Idempotency Check
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<PlaceOrderResponse> cached = idempotencyGuard.lookup(idempotencyKey);
            if (cached.isPresent()) {
                log.info("Idempotent hit for key [{}]. Returning cached order response.", idempotencyKey);
                return cached.get();
            }
        }

        String orderId = UUID.randomUUID().toString();
        Order order = new Order(orderId, req.userId(), req.side(), req.type(), req.price(), req.quantity(), Instant.now());

        // 2. Fund Reservation (Buyer reserves price * quantity; Market buy reserves max 100 * quantity)
        long reserveAmount = 0;
        if (order.side() == Side.BUY) {
            long unitPrice = (order.type() == OrderType.MARKET) ? 100 : order.price();
            reserveAmount = unitPrice * order.quantity();
            ledger.reserve(order.userId(), reserveAmount);
        }

        // 3. Market State Gate & Matching
        OrderBook.MatchResult matchResult;
        try {
            matchResult = market.placeOrder(order);
        } catch (Exception ex) {
            // Refund full reservation if matching or market state rejects the order
            if (reserveAmount > 0) {
                ledger.refund(order.userId(), reserveAmount);
            }
            throw ex;
        }

        // 4. Trade Settlement & Surplus Refund
        List<TradeDto> tradeDtos = new ArrayList<>();
        long totalCostFilled = 0;

        for (Trade trade : matchResult.trades()) {
            tradeDtos.add(new TradeDto(trade.buyOrderId(), trade.sellOrderId(), trade.price(), trade.quantity(), trade.timestamp()));
            long tradeValue = trade.price() * trade.quantity();
            totalCostFilled += tradeValue;

            // Settle trade funds
            ledger.settleTrade(order.side() == Side.BUY ? order.userId() : "COUNTERPARTY",
                               order.side() == Side.SELL ? order.userId() : "COUNTERPARTY",
                               tradeValue);

            // Publish Trade Event
            eventBus.publish(new Event(EventType.TRADE_EXECUTED, trade));
        }

        // Refund any surplus from price improvement or uncrossed IOC/Market orders
        if (order.side() == Side.BUY && reserveAmount > 0) {
            long restingReserved = matchResult.restingOrder().map(r -> r.price() * r.quantity()).orElse(0L);
            long surplus = reserveAmount - (totalCostFilled + restingReserved);
            if (surplus > 0) {
                ledger.refund(order.userId(), surplus);
            }
        }

        eventBus.publish(new Event(EventType.ORDER_PLACED, order));

        String status = matchResult.remainingQuantity() == 0 ? "FILLED"
                : (tradeDtos.isEmpty() ? "RESTING" : "PARTIALLY_FILLED");

        PlaceOrderResponse response = new PlaceOrderResponse(orderId, status, matchResult.remainingQuantity(), tradeDtos);

        // 5. Idempotency Store
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyGuard.store(idempotencyKey, response, Duration.ofMinutes(10));
        }

        return response;
    }

    public Order cancelOrder(String orderId) {
        Order cancelled = market.cancelOrder(orderId);
        // Refund remaining reserved balance for buy orders
        if (cancelled.side() == Side.BUY) {
            ledger.refund(cancelled.userId(), cancelled.price() * cancelled.quantity());
        }
        eventBus.publish(new Event(EventType.ORDER_CANCELLED, cancelled));
        return cancelled;
    }

    public Market getMarket() { return market; }
    public Ledger getLedger() { return ledger; }
    public EventBus getEventBus() { return eventBus; }
    public IdempotencyGuard getIdempotencyGuard() { return idempotencyGuard; }
}
