package com.orderbook;

import com.orderbook.domain.Order;
import com.orderbook.domain.OrderType;
import com.orderbook.domain.Side;
import com.orderbook.ledger.InsufficientFundsException;
import com.orderbook.ledger.Ledger;
import com.orderbook.ledger.UserBalance;
import com.orderbook.market.InvalidStateTransitionException;
import com.orderbook.market.Market;
import com.orderbook.market.MarketNotLiveException;
import com.orderbook.market.MarketState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketAndLedgerTest {

    @Test
    @DisplayName("Market State Machine transitions and gating rules")
    void testMarketLifecycleAndGating() {
        Market market = new Market();
        assertThat(market.getState()).isEqualTo(MarketState.OPEN);

        Order buy = new Order("b1", "u1", Side.BUY, OrderType.LIMIT, 50, 5, Instant.now());

        // Cannot place order in OPEN state
        assertThatThrownBy(() -> market.placeOrder(buy))
                .isInstanceOf(MarketNotLiveException.class);

        // Open -> Live (valid)
        market.transition(MarketState.LIVE);
        assertThat(market.getState()).isEqualTo(MarketState.LIVE);

        // Now order placement succeeds
        var res = market.placeOrder(buy);
        assertThat(res.remainingQuantity()).isEqualTo(5);

        // Live -> Paused
        market.transition(MarketState.PAUSED);
        assertThat(market.getState()).isEqualTo(MarketState.PAUSED);
        // Placing in PAUSED throws
        assertThatThrownBy(() -> market.placeOrder(buy))
                .isInstanceOf(MarketNotLiveException.class);

        // Cancel order is allowed in PAUSED
        Order cancelled = market.cancelOrder("b1");
        assertThat(cancelled.id()).isEqualTo("b1");

        // Paused -> Settled
        market.transition(MarketState.SETTLED);
        assertThat(market.getState()).isEqualTo(MarketState.SETTLED);

        // Settled is terminal; cannot transition out
        assertThatThrownBy(() -> market.transition(MarketState.LIVE))
                .isInstanceOf(InvalidStateTransitionException.class);

        // Cancel is blocked in SETTLED
        assertThatThrownBy(() -> market.cancelOrder("b1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Ledger TOCTOU safety, reservation, settlement, and conservation invariant")
    void testLedgerOperationsAndInvariants() {
        Ledger ledger = new Ledger();

        ledger.credit("buyer", 10_000);  // 100.00 TL
        ledger.credit("seller", 5_000);   // 50.00 TL
        long initialTotal = ledger.total();
        assertThat(initialTotal).isEqualTo(15_000);

        // 1. Reserve funds for order
        ledger.reserve("buyer", 6_400); // 64¢ * 100 lots
        UserBalance buyerBal = ledger.getBalance("buyer").orElseThrow();
        assertThat(buyerBal.available()).isEqualTo(10_000);
        assertThat(buyerBal.held()).isEqualTo(6_400);
        assertThat(buyerBal.free()).isEqualTo(3_600);

        // Overdraft attempt fails atomically (TOCTOU safe)
        assertThatThrownBy(() -> ledger.reserve("buyer", 4_000))
                .isInstanceOf(InsufficientFundsException.class);

        // 2. Settle trade: 64¢ * 50 lots = 3,200¢ transferred
        ledger.settleTrade("buyer", "seller", 3_200);

        buyerBal = ledger.getBalance("buyer").orElseThrow();
        assertThat(buyerBal.available()).isEqualTo(6_800); // 10000 - 3200
        assertThat(buyerBal.held()).isEqualTo(3_200);      // 6400 - 3200

        UserBalance sellerBal = ledger.getBalance("seller").orElseThrow();
        assertThat(sellerBal.available()).isEqualTo(8_200); // 5000 + 3200

        // 3. Refund remaining held funds
        ledger.refund("buyer", 3_200);
        buyerBal = ledger.getBalance("buyer").orElseThrow();
        assertThat(buyerBal.held()).isEqualTo(0);
        assertThat(buyerBal.free()).isEqualTo(6_800);

        // Conservation Invariant: total money in ledger must remain constant!
        assertThat(ledger.total()).isEqualTo(initialTotal);
    }
}
