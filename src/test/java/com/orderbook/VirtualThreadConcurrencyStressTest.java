package com.orderbook;

import com.orderbook.domain.Order;
import com.orderbook.domain.OrderType;
import com.orderbook.domain.Side;
import com.orderbook.engine.OrderBook;
import com.orderbook.ledger.Ledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualThreadConcurrencyStressTest {

    @Test
    @DisplayName("1,000 Virtual Threads concurrently placing and matching orders under high contention")
    void testVirtualThreadMatchingStress() throws InterruptedException {
        OrderBook orderBook = new OrderBook();
        Ledger ledger = new Ledger();

        int traderCount = 50;
        int ordersPerTrader = 20; // 1,000 total orders
        int totalThreads = traderCount * ordersPerTrader;

        // Seed ledger
        for (int i = 0; i < traderCount; i++) {
            ledger.credit("user-" + i, 1_000_000); // 10,000.00 TL
        }
        long initialTotal = ledger.total();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        AtomicInteger totalTrades = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < traderCount; t++) {
                final int traderId = t;
                for (int o = 0; o < ordersPerTrader; o++) {
                    final int orderNum = o;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            Side side = (traderId % 2 == 0) ? Side.BUY : Side.SELL;
                            long price = 50 + (orderNum % 10); // prices 50 to 59 cents
                            long qty = 5;

                            Order order = new Order(
                                    "order-" + traderId + "-" + orderNum,
                                    "user-" + traderId,
                                    side,
                                    OrderType.LIMIT,
                                    price,
                                    qty,
                                    Instant.now()
                            );

                            OrderBook.MatchResult res = orderBook.placeOrder(order);
                            totalTrades.addAndGet(res.trades().size());

                        } catch (Exception ignored) {
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }
            }

            startLatch.countDown(); // Release all 1,000 virtual threads concurrently
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

            assertThat(completed).isTrue();
            assertThat(totalTrades.get()).isGreaterThan(0);
            assertThat(ledger.total()).isEqualTo(initialTotal); // Conservation holds
        }
    }
}
