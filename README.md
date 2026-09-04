# 📈 java-order-book: Production Prediction-Market Order Book & Matching Engine

A production-quality **prediction-market order book and matching engine** built in **Modern Java 21+** and **Spring Boot 3.4.x**.

This project translates and enhances the design of the Go [`fanatics-order-book/`](../fanatics-order-book/) architecture, strictly utilizing modern Java language primitives: **Java Records**, **Java 21 Virtual Threads (Project Loom)**, **Streams API**, **Sealed Types**, and **Pattern Matching**.

---

## 📑 Table of Contents

1. [What is an Order Book?](#what-is-an-order-book)
2. [Domain: Prediction Markets](#domain-prediction-markets)
3. [Architecture at a Glance](#architecture-at-a-glance)
4. [Go vs Modern Java 21+ Architectural Comparison](#go-vs-modern-java-21-architectural-comparison)
5. [Core Architectural Components & Patterns](#core-architectural-components--patterns)
   - [Domain Entities: Java Records & Immutability](#1-domain-entities-java-records--immutability)
   - [OrderBook: Price-Time Priority & 3-Key Comparator](#2-orderbook-price-time-priority--3-key-comparator)
   - [Matching Engine: Limit, Market & IOC Matching](#3-matching-engine-limit-market--ioc-matching)
   - [Self-Match Prevention & Price Improvement](#4-self-match-prevention--price-improvement)
   - [Market Lifecycle State Machine](#5-market-lifecycle-state-machine)
   - [Ledger: TOCTOU-Safe Balances & Conservation Invariant](#6-ledger-toctou-safe-balances--conservation-invariant)
   - [Idempotency Guard & Virtual Thread Event Bus](#7-idempotency-guard--virtual-thread-event-bus)
6. [API Reference](#api-reference)
7. [Running the Application & Tests](#running-the-application--tests)

---

## What is an Order Book?

An **order book** is the central matching data structure of an exchange. It organizes resting orders into two sorted queues:

```
BIDS (buyers: highest price first)    ASKS (sellers: lowest price first)
──────────────────────────────────    ──────────────────────────────────
price 67¢  qty 5                      price 64¢  qty 10  ← best ask (lowest)
price 65¢  qty 12                     price 68¢  qty 3
price 63¢  qty 7                      price 70¢  qty 20
```

When an aggressive order arrives, the matching engine tests whether it **crosses** the opposite side. A buy order at 65¢ crosses an ask resting at 64¢. They execute at **64¢** (the resting order's **passive price**, awarding *price improvement* to the aggressive buyer).

### Core Exchange Invariants:
1. **Price-Time Priority:** Lowest asks and highest bids execute first; FIFO ordering breaks ties at identical prices.
2. **Self-Match Prevention:** An order never fills against another order submitted by the same `userId`.
3. **Price Improvement:** The aggressor receives the resting order's passive price, never paying more than the resting price.

---

## Domain: Prediction Markets

This order book models binary event contracts (e.g., *"Will team X win the tournament?"*):
- Prices are **integer cents in $[1, 100]$** representing probability percentages (64¢ = 64% likelihood).
- For `MARKET` orders, price is `0` (sentinel indicating no price limit).
- All arithmetic uses **`long` integer cents** (floating-point `double/float` is strictly prohibited in financial math).
- A contract settles at either **0¢** (NO) or **100¢** (YES).

---

## Architecture at a Glance

```
HTTP POST /api/v1/orders
           │
           ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        OrderBookApiController                          │
│     Extracts Idempotency-Key, validates PlaceOrderRequest record       │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       OrderOrchestratorService                         │
│  1. Idempotency Check → returns cached response on duplicate key       │
│  2. Ledger.reserve()  → atomic check-and-hold (TOCTOU-safe)            │
│  3. Market.placeOrder()                                                │
│      ├── Market State Gate (must be LIVE)                              │
│      └── OrderBook Matching Engine                                     │
│            ├── Price-Time Priority sort                                │
│            ├── Price Improvement execution                             │
│            └── Self-Match Prevention                                   │
│  4. Ledger.settleTrade() & refund unexecuted/surplus amounts           │
│  5. EventBus.publish() via Java 21 Virtual Threads                     │
│  6. IdempotencyGuard.store() response with TTL                         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Go vs Modern Java 21+ Architectural Comparison

| Architectural Aspect | Go Implementation (`fanatics-order-book/`) | Modern Java Implementation (`java-order-book/`) | Advantage / Why Java Approach |
|:---|:---|:---|:---|
| **Domain Entities** | Go `struct` (pass-by-value) | **Java Records** (`record Order`, `record Trade`) | Compile-time immutability, compact constructor invariant validation, zero boilerplate. |
| **Concurrency Scaling** | Goroutines (`go func()`) | **Java 21 Virtual Threads (Loom)** (`spring.threads.virtual.enabled=true`) | Millions of concurrent lightweight threads managed by JVM ForkJoinPool; synchronous style with zero OS thread bloat. |
| **Sorting Invariant** | `sort.Search` + slice shifting | `Collections.binarySearch` + 3-Key Comparator | Deterministic replay; strict tiebreaking on Price, Timestamp, and ID. |
| **Depth Aggregation** | Manual slice loop | **Java Streams API** (`Collectors.groupingBy`, `summingLong`) | Declarative, thread-safe depth and volume calculation across price levels. |
| **State Machine** | Map transition table + mutex | **Data-driven Enum / Sealed State Hierarchy** | Type-safe transition validation and gating (orders rejected outside LIVE). |
| **Event Bus** | Channel fan-out with drop policy | **Virtual Thread Asynchronous EventBus** | Slow subscribers never stall the core matching engine; zero carrier thread pinning. |
| **Data Integrity** | Manual mutex in Ledger | **Atomic Critical Section & Conservation Invariant** | Guaranteed balance preservation ($\sum balances + \sum held = \text{constant}$). |

---

## Core Architectural Components & Patterns

### 1. Domain Entities: Java Records & Immutability

```java
public record Order(
        String id,
        String userId,
        Side side,
        OrderType type,
        long price,
        long quantity,
        Instant timestamp
) {
    public Order {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Order ID cannot be empty");
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (type == OrderType.MARKET && price != 0) {
            throw new IllegalArgumentException("Market order price must be 0");
        }
        if (type != OrderType.MARKET && (price < 1 || price > 100)) {
            throw new IllegalArgumentException("Prediction market price must be in [1, 100] cents");
        }
    }
}
```

---

### 2. OrderBook: Price-Time Priority & 3-Key Comparator

The 3-key comparator guarantees deterministic sorting:
```java
private static final Comparator<Order> BID_COMPARATOR = Comparator
        .comparingLong(Order::price).reversed()
        .thenComparing(Order::timestamp)
        .thenComparing(Order::id);

private static final Comparator<Order> ASK_COMPARATOR = Comparator
        .comparingLong(Order::price)
        .thenComparing(Order::timestamp)
        .thenComparing(Order::id);
```

---

### 3. Matching Engine: Limit, Market & IOC Matching

- **Limit:** Matches crossing orders at or better than limit price; rests remainder in sorted list.
- **Market:** Sweeps opposite book at any price. If liquidity is exhausted, throws `MarketNoLiquidityException`.
- **IOC (Immediate-or-Cancel):** Fills as much as crosses within price limit; any uncrossed remainder is cancelled immediately and never rests on the book.

---

### 4. Self-Match Prevention & Price Improvement

```java
// Self-match prevention in Limit orders:
if (bestAsk.userId().equals(o.userId())) {
    break; // Stop matching to avoid trading with oneself
}

// Price improvement:
// Aggressor buying at 67¢ crossing resting ask at 64¢ trades at 64¢:
trades.add(new Trade(o.id(), bestAsk.id(), bestAsk.price(), matchQty, Instant.now()));
```

---

### 5. Market Lifecycle State Machine

```
OPEN ──> LIVE ──> PAUSED ──> SETTLED (terminal)
          │         │
          ▼         ▼
      [SETTLED] [SETTLED]
```

- `placeOrder`: Permitted **only** when state is `LIVE`.
- `cancelOrder`: Permitted in `OPEN`, `LIVE`, `PAUSED`; strictly rejected in `SETTLED`.

---

### 6. Ledger: TOCTOU-Safe Balances & Conservation Invariant

```java
public void reserve(String userId, long amountCents) {
    lock.lock();
    try {
        long free = balances.getOrDefault(userId, 0L) - held.getOrDefault(userId, 0L);
        if (free < amountCents) {
            throw new InsufficientFundsException("Insufficient funds");
        }
        held.put(userId, held.getOrDefault(userId, 0L) + amountCents);
    } finally {
        lock.unlock();
    }
}
```

- Check-and-reserve is executed in a single atomic section, eliminating **Time-of-Check to Time-of-Use (TOCTOU)** double-spend vulnerabilities.
- Total money in circulation is verified by `ledger.total()`.

---

### 7. Idempotency Guard & Virtual Thread Event Bus

- **Idempotency Guard:** Caches responses keyed by `Idempotency-Key` header with TTL and automatic expiration.
- **Event Bus:** Dispatches domain events (`ORDER_PLACED`, `TRADE_EXECUTED`, `ORDER_CANCELLED`) asynchronously on lightweight Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

---

## API Reference

| Method | Path | Description | Headers |
|---|---|---|---|
| `POST` | `/api/v1/orders` | Place Limit, Market, or IOC order | `Idempotency-Key` (Optional) |
| `DELETE` | `/api/v1/orders/{id}` | Cancel resting order | - |
| `GET` | `/api/v1/orderbook` | Order book snapshot (bids, asks, spread) | - |
| `GET` | `/api/v1/orderbook/depth` | Aggregated price depth levels | - |
| `GET` | `/api/v1/market/state` | Current market state (`OPEN`, `LIVE`, etc.) | - |
| `POST` | `/api/v1/market/transition` | Transition market state | - |
| `POST` | `/api/v1/ledger/{userId}/credit` | Credit funds to user | - |
| `GET` | `/api/v1/ledger/{userId}` | Retrieve available, held, and free balance | - |

---

## Running the Application & Tests

### Prerequisites
- **Java 21+** (JDK 21 or 25)
- **Maven 3.9+**

### Run Test Suite
Executes unit tests, 1,000 Virtual Thread stress tests, and Spring Boot MockMvc integration tests:

```bash
cd java-order-book
mvn test -o
```

Expected output:
```
[INFO] -------------------< com.orderbook:java-order-book >--------------------
[INFO] Building java-order-book 1.0.0
[INFO] Results:
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Launch Spring Boot Application
```bash
cd java-order-book
mvn spring-boot:run -o
```
Server starts on `http://localhost:8080`.
Actuator metrics available at `http://localhost:8080/actuator/health`.
