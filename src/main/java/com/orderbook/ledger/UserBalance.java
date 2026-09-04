package com.orderbook.ledger;

/**
 * Immutable representation of a user's ledger account.
 *
 * @param available Total deposited balance
 * @param held      Funds currently locked/reserved in open orders
 * @param free      Available spending balance (available - held)
 */
public record UserBalance(long available, long held, long free) {
    public UserBalance(long available, long held) {
        this(available, held, available - held);
    }
}
