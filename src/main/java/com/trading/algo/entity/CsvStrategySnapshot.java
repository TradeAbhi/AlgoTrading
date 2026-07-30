package com.trading.algo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Stores a snapshot of CSV strategy scan results.
 * Used to track which stocks appeared in previous scans to identify new entries.
 */
@Entity
@Table(name = "csv_strategy_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvStrategySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Trading date for this snapshot
     */
    @Column(name = "scan_date", nullable = false)
    private LocalDate scanDate;

    /**
     * JSON string containing Fibonacci 15m signals (BUY and SELL symbols)
     */
    @Column(name = "fibonacci_15m_data", columnDefinition = "TEXT")
    private String fibonacci15mData;

    /**
     * JSON string containing Fibonacci 5m signals (BUY and SELL symbols)
     */
    @Column(name = "fibonacci_5m_data", columnDefinition = "TEXT")
    private String fibonacci5mData;

    /**
     * JSON string containing ORB signals (BUY and SELL symbols)
     */
    @Column(name = "orb_data", columnDefinition = "TEXT")
    private String orbData;

    /**
     * When this snapshot was saved
     */
    @Column(name = "saved_at")
    private LocalDateTime savedAt;
}
