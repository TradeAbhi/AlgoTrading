package com.trading.algo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Stores analysis of winning backtest trades to identify patterns
 * that caused the price movement.
 */
@Entity
@Table(name = "backtest_winner_analysis", indexes = {
    @Index(name = "idx_symbol_date", columnList = "symbol, trade_date"),
    @Index(name = "idx_strategy", columnList = "strategy_name"),
    @Index(name = "idx_outcome", columnList = "outcome")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestWinnerAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Trade identification ────────────────────────────────────────────────
    private String symbol;
    private LocalDate tradeDate;
    private String strategyName;  // e.g., "ORB", "DELTA", "FIBO", "IPO"

    @Enumerated(EnumType.STRING)
    private BacktestTrade.Outcome outcome;

    @Enumerated(EnumType.STRING)
    private BacktestTrade.Direction direction;

    // ── Trade metrics ────────────────────────────────────────────────────────
    private Double entryPrice;
    private Double exitPrice;
    private Double pnlPercent;
    private Double actualRR;
    private Double riskPoints;

    // ── Candle characteristics (C1 = 9:15-9:30) ───────────────────────────────
    private Double c1WickRatio;
    private Double c1BodyPct;
    private Double c1RangePct;
    private Long c1Volume;

    // ── Technical indicators ───────────────────────────────────────────────────
    private Double prevRsi;           // RSI(14) before trade
    private Double atrRatio;          // Today's range / 14-day ATR
    private Boolean above20Dma;        // Was price above 20-day MA?
    private Double prevDayChangePct;   // Previous day's % change
    private Double gapPercent;        // Gap from previous close

    // ── Volume analysis ───────────────────────────────────────────────────────
    private Double volumeRatio;       // Today's volume / 5-day avg volume
    private Boolean volumeFlag;        // C1 volume >= 1.5x C2 volume

    // ── Pattern flags ───────────────────────────────────────────────────────
    private Boolean strongOpening;    // C1 body > 1% and wick ratio > 0.6
    private Boolean breakout;         // Price broke previous day high/low
    private Boolean highVolBreakout;  // Breakout with volume > 2x avg

    // ── Metadata ──────────────────────────────────────────────────────────────
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "analysis_notes")
    private String analysisNotes;    // Free text for manual insights
}
