package com.orderbook.market;

import java.util.List;
import java.util.Map;

/**
 * Market Lifecycle State Machine (Category 05 & 23).
 * <p>
 * Transitions:
 * <ul>
 *   <li>OPEN    → [LIVE]</li>
 *   <li>LIVE    → [PAUSED, SETTLED]</li>
 *   <li>PAUSED  → [LIVE, SETTLED]</li>
 *   <li>SETTLED → [] (Terminal)</li>
 * </ul>
 */
public enum MarketState {
    OPEN,
    LIVE,
    PAUSED,
    SETTLED;

    private static final Map<MarketState, List<MarketState>> VALID_TRANSITIONS = Map.of(
            OPEN, List.of(LIVE),
            LIVE, List.of(PAUSED, SETTLED),
            PAUSED, List.of(LIVE, SETTLED),
            SETTLED, List.of()
    );

    public boolean canTransitionTo(MarketState next) {
        return VALID_TRANSITIONS.getOrDefault(this, List.of()).contains(next);
    }
}
