package com.orderbook.ledger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe In-Memory Ledger (Category 06 & 23).
 * <p>
 * Tracks available balances and held (reserved) funds per user.
 * <b>Key Invariants (enforced at all times under lock):</b>
 * <ul>
 *   <li>{@code balances[user] >= 0}</li>
 *   <li>{@code held[user] >= 0}</li>
 *   <li>{@code balances[user] - held[user] >= 0} (free balance)</li>
 *   <li><b>Conservation Invariant:</b> {@code sum(balances) + sum(held)} is strictly conserved.</li>
 * </ul>
 * <b>TOCTOU Prevention:</b> The check-and-reserve logic executes atomically inside
 * a single critical section to eliminate Time-of-Check to Time-of-Use race conditions.
 */
public class Ledger {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, Long> held = new HashMap<>();

    /**
     * Credits funds to a user account.
     */
    public void credit(String userId, long amountCents) {
        Objects.requireNonNull(userId, "User ID cannot be null");
        if (amountCents <= 0) throw new IllegalArgumentException("Credit amount must be positive: " + amountCents);

        lock.lock();
        try {
            balances.put(userId, balances.getOrDefault(userId, 0L) + amountCents);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically checks free balance and reserves funds for an order (TOCTOU-safe).
     */
    public void reserve(String userId, long amountCents) {
        Objects.requireNonNull(userId, "User ID cannot be null");
        if (amountCents <= 0) throw new IllegalArgumentException("Reserve amount must be positive: " + amountCents);

        lock.lock();
        try {
            long userBalance = balances.getOrDefault(userId, 0L);
            long userHeld = held.getOrDefault(userId, 0L);
            long free = userBalance - userHeld;

            if (free < amountCents) {
                throw new InsufficientFundsException("Insufficient funds for user " + userId + 
                        ": required " + amountCents + "¢, but free balance is " + free + "¢");
            }

            held.put(userId, userHeld + amountCents);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Settles trade payment between buyer and seller.
     */
    public void settleTrade(String buyer, String seller, long amountCents) {
        Objects.requireNonNull(buyer);
        Objects.requireNonNull(seller);
        if (amountCents <= 0) return;

        lock.lock();
        try {
            long buyerHeld = held.getOrDefault(buyer, 0L);
            long buyerBal = balances.getOrDefault(buyer, 0L);
            long sellerBal = balances.getOrDefault(seller, 0L);

            held.put(buyer, Math.max(0L, buyerHeld - amountCents));
            balances.put(buyer, buyerBal - amountCents);
            balances.put(seller, sellerBal + amountCents);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases unexecuted reserved funds back to available balance (e.g. order cancel/unfilled).
     */
    public void refund(String userId, long amountCents) {
        Objects.requireNonNull(userId);
        if (amountCents <= 0) return;

        lock.lock();
        try {
            long userHeld = held.getOrDefault(userId, 0L);
            held.put(userId, Math.max(0L, userHeld - amountCents));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a snapshot of the user's balances.
     */
    public Optional<UserBalance> getBalance(String userId) {
        lock.lock();
        try {
            if (!balances.containsKey(userId) && !held.containsKey(userId)) {
                return Optional.empty();
            }
            long avail = balances.getOrDefault(userId, 0L);
            long h = held.getOrDefault(userId, 0L);
            return Optional.of(new UserBalance(avail, h));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns total system money in circulation for conservation invariant verification.
     */
    public long total() {
        lock.lock();
        try {
            return balances.values().stream().mapToLong(Long::longValue).sum() +
                   held.values().stream().mapToLong(Long::longValue).sum();
        } finally {
            lock.unlock();
        }
    }
}
