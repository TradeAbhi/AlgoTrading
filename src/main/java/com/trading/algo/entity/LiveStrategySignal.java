package com.trading.algo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Stores live strategy signals generated from momentum stock snapshots.
 * Used to track which stocks had Fibonacci or ORB signals applied on a given day.
 */
@Entity
@Table(name = "live_strategy_signal")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveStrategySignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Strategy type that generated this signal
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false)
    private StrategyType strategyType;

    /**
     * Stock symbol
     */
    @Column(name = "symbol", nullable = false)
    private String symbol;

    /**
     * Trade direction (BUY or SELL)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private Direction direction;

    /**
     * Date when the signal was generated
     */
    @Column(name = "signal_date", nullable = false)
    private LocalDate signalDate;

    /**
     * Time when the signal was generated
     */
    @Column(name = "signal_time", nullable = false)
    private LocalTime signalTime;

    /**
     * Entry price
     */
    @Column(name = "entry_price")
    private Double entryPrice;

    /**
     * Stop loss level
     */
    @Column(name = "stop_loss")
    private Double stopLoss;

    /**
     * Target price
     */
    @Column(name = "target")
    private Double target;

    /**
     * Breakout level (for ORB) or reference level
     */
    @Column(name = "breakout_level")
    private Double breakoutLevel;

    /**
     * A/D ratio at the time of signal generation
     */
    @Column(name = "ad_ratio")
    private Double adRatio;

    /**
     * Whether the signal was approved by A/D ratio filter
     */
    @Column(name = "ad_approved")
    private Boolean adApproved;

    /**
     * Momentum snapshot ID this signal was generated from
     */
    @Column(name = "momentum_snapshot_id")
    private Long momentumSnapshotId;

    /**
     * Additional signal details (JSON)
     */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /**
     * When this signal was created
     */
    @Column(name = "created_at")
    private LocalDate createdAt = LocalDate.now();

    public enum StrategyType {
        FIBONACCI,
        ORB
    }

    public enum Direction {
        BUY,
        SELL
    }
}
