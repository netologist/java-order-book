package com.orderbook;

import com.orderbook.domain.*;
import com.orderbook.engine.OrderBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookMatchingTest {

    private OrderBook orderBook;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook();
    }

    @Test
    @DisplayName("Limit order match: crosses opposite side with price improvement")
    void testLimitOrderPriceImprovement() {
        // Resting ask at 64¢ for 10 lots
        Order ask = new Order("ask-1", "user-seller", Side.SELL, OrderType.LIMIT, 64, 10, Instant.now());
        orderBook.placeOrder(ask);

        // Incoming aggressive buy at 67¢ for 5 lots
        Order buy = new Order("buy-1", "user-buyer", Side.BUY, OrderType.LIMIT, 67, 5, Instant.now());
        OrderBook.MatchResult result = orderBook.placeOrder(buy);

        assertThat(result.trades()).hasSize(1);
        Trade trade = result.trades().get(0);
        assertThat(trade.buyOrderId()).isEqualTo("buy-1");
        assertThat(trade.sellOrderId()).isEqualTo("ask-1");
        assertThat(trade.quantity()).isEqualTo(5);
        // Price improvement: executed at 64¢ (resting passive price), not 67¢!
        assertThat(trade.price()).isEqualTo(64);

        assertThat(result.remainingQuantity()).isEqualTo(0);
        assertThat(result.restingOrder()).isEmpty();

        // Remaining 5 lots of ask should still rest on the book
        assertThat(orderBook.askCount()).isEqualTo(1);
        assertThat(orderBook.getBestAsk().orElseThrow().quantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("Limit order partial fill: remainder rests on the book")
    void testLimitOrderPartialFill() {
        // Resting ask at 60¢ for 4 lots
        orderBook.placeOrder(new Order("ask-1", "seller", Side.SELL, OrderType.LIMIT, 60, 4, Instant.now()));

        // Incoming buy for 10 lots at 60¢
        Order buy = new Order("buy-1", "buyer", Side.BUY, OrderType.LIMIT, 60, 10, Instant.now());
        OrderBook.MatchResult result = orderBook.placeOrder(buy);

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).quantity()).isEqualTo(4);
        assertThat(result.remainingQuantity()).isEqualTo(6);
        assertThat(result.restingOrder()).isPresent();
        assertThat(result.restingOrder().get().quantity()).isEqualTo(6);

        // Book now has 0 asks and 1 resting bid for 6 lots
        assertThat(orderBook.askCount()).isEqualTo(0);
        assertThat(orderBook.bidCount()).isEqualTo(1);
        assertThat(orderBook.getBestBid().orElseThrow().quantity()).isEqualTo(6);
    }

    @Test
    @DisplayName("Price-Time Priority: lower asks and older orders fill first")
    void testPriceTimePriority() {
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-01T10:00:01Z");
        Instant t3 = Instant.parse("2026-09-01T10:00:02Z");

        // Ask at 65¢ (older)
        orderBook.placeOrder(new Order("ask-1", "s1", Side.SELL, OrderType.LIMIT, 65, 5, t1));
        // Ask at 64¢ (better price)
        orderBook.placeOrder(new Order("ask-2", "s2", Side.SELL, OrderType.LIMIT, 64, 5, t2));
        // Ask at 65¢ (newer)
        orderBook.placeOrder(new Order("ask-3", "s3", Side.SELL, OrderType.LIMIT, 65, 5, t3));

        // Incoming buy for 8 lots at 65¢
        Order buy = new Order("buy-1", "buyer", Side.BUY, OrderType.LIMIT, 65, 8, Instant.now());
        var res = orderBook.placeOrder(buy);

        assertThat(res.trades()).hasSize(2);
        // 1st fill: best price ask-2 at 64¢ (5 lots)
        assertThat(res.trades().get(0).sellOrderId()).isEqualTo("ask-2");
        assertThat(res.trades().get(0).price()).isEqualTo(64);
        // 2nd fill: time-priority ask-1 at 65¢ (3 lots)
        assertThat(res.trades().get(1).sellOrderId()).isEqualTo("ask-1");
        assertThat(res.trades().get(1).price()).isEqualTo(65);
        assertThat(res.trades().get(1).quantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("Self-Match Prevention: an order never matches against same user ID")
    void testSelfMatchPrevention() {
        // Resting ask by user-alpha at 50¢
        orderBook.placeOrder(new Order("ask-1", "user-alpha", Side.SELL, OrderType.LIMIT, 50, 10, Instant.now()));

        // Incoming buy also by user-alpha at 55¢
        Order buy = new Order("buy-1", "user-alpha", Side.BUY, OrderType.LIMIT, 55, 5, Instant.now());
        var res = orderBook.placeOrder(buy);

        // Self-match prevented: 0 trades executed!
        assertThat(res.trades()).isEmpty();
        // Buy rests on book without crossing own ask
        assertThat(orderBook.bidCount()).isEqualTo(1);
        assertThat(orderBook.askCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Market Order sweeps book at any price and throws if liquidity exhausted")
    void testMarketOrderSweep() {
        orderBook.placeOrder(new Order("ask-1", "s1", Side.SELL, OrderType.LIMIT, 60, 5, Instant.now()));
        orderBook.placeOrder(new Order("ask-2", "s2", Side.SELL, OrderType.LIMIT, 70, 5, Instant.now()));

        // Market buy for 10 lots (price must be 0)
        Order marketBuy = new Order("mb-1", "buyer", Side.BUY, OrderType.MARKET, 0, 10, Instant.now());
        var res = orderBook.placeOrder(marketBuy);

        assertThat(res.trades()).hasSize(2);
        assertThat(res.trades().get(0).price()).isEqualTo(60);
        assertThat(res.trades().get(1).price()).isEqualTo(70);
        assertThat(orderBook.askCount()).isEqualTo(0);

        // Another market order when empty book throws MarketNoLiquidityException
        Order emptyBuy = new Order("mb-2", "buyer", Side.BUY, OrderType.MARKET, 0, 5, Instant.now());
        assertThatThrownBy(() -> orderBook.placeOrder(emptyBuy))
                .isInstanceOf(MarketNoLiquidityException.class);
    }

    @Test
    @DisplayName("IOC (Immediate-or-Cancel): fills what crosses, cancels remainder")
    void testIocOrder() {
        // Resting ask at 60¢ for 4 lots
        orderBook.placeOrder(new Order("ask-1", "s1", Side.SELL, OrderType.LIMIT, 60, 4, Instant.now()));

        // IOC buy for 10 lots at 60¢
        Order iocBuy = new Order("ioc-1", "buyer", Side.BUY, OrderType.IOC, 60, 10, Instant.now());
        var res = orderBook.placeOrder(iocBuy);

        assertThat(res.trades()).hasSize(1);
        assertThat(res.trades().get(0).quantity()).isEqualTo(4);
        assertThat(res.remainingQuantity()).isEqualTo(6);
        // IOC remainder is NOT rested
        assertThat(res.restingOrder()).isEmpty();
        assertThat(orderBook.bidCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("CancelOrder removes order from book; double-cancel throws OrderNotFoundException")
    void testCancelOrder() {
        orderBook.placeOrder(new Order("ask-1", "seller", Side.SELL, OrderType.LIMIT, 70, 10, Instant.now()));
        assertThat(orderBook.askCount()).isEqualTo(1);

        Order cancelled = orderBook.cancelOrder("ask-1");
        assertThat(cancelled.id()).isEqualTo("ask-1");
        assertThat(orderBook.askCount()).isEqualTo(0);

        assertThatThrownBy(() -> orderBook.cancelOrder("ask-1"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("Stream-based depth aggregation")
    void testDepthAggregation() {
        orderBook.placeOrder(new Order("b1", "u1", Side.BUY, OrderType.LIMIT, 50, 10, Instant.now()));
        orderBook.placeOrder(new Order("b2", "u2", Side.BUY, OrderType.LIMIT, 50, 15, Instant.now()));
        orderBook.placeOrder(new Order("b3", "u3", Side.BUY, OrderType.LIMIT, 45, 20, Instant.now()));

        Map<Long, Long> depth = orderBook.getDepth(Side.BUY);
        assertThat(depth).containsEntry(50L, 25L);
        assertThat(depth).containsEntry(45L, 20L);
    }
}
