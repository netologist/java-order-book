package com.orderbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Production Prediction-Market Order Book & Matching Engine.
 * <p>
 * Implemented using Modern Java 21+ and Spring Boot 3.4.
 * Features:
 * <ul>
 *   <li><b>Records:</b> Immutable orders, trades, snapshots, and API DTOs.</li>
 *   <li><b>Virtual Threads:</b> Enabled via {@code spring.threads.virtual.enabled=true} for high-throughput concurrency.</li>
 *   <li><b>Streams:</b> Order filtering, price level depth aggregation, volume metrics.</li>
 *   <li><b>Sealed Types & Pattern Matching:</b> State machine transitions and order validation.</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class OrderBookApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderBookApplication.class, args);
    }
}
