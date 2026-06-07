package com.trading.algo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Backtest results for index instruments (Nifty 50, Bank Nifty, Nifty Fin Service).
 * Same Opening Candle Strategy rules as BacktestTrade but for indexes.
 * Kept as a separate table so index results don't pollute F&O stock results.
 */
@Entity
@Table(name = "index_backtest_trade")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexBacktestTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** INDEX name: NIFTY_50 / BANK_NIFTY / FIN_NIFTY */
    @Enumerated(EnumType.STRING)
    private IndexName indexName;

    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    private BacktestTrade.Direction direction;   // BUY or SELL

    // ── Candle 1 (9:15–9:30) ─────────────────────────────────────────────
    private double c1Open;
    private double c1High;
    private double c1Low;
    private double c1Close;
    private double c1WickRatio;

    // ── Candle 2 (9:30–9:45) ─────────────────────────────────────────────
    private double c2Open;
    private double c2High;
    private double c2Low;
    private double c2Close;

    // ── Trade levels ─────────────────────────────────────────────────────
    private double entryPrice;
    private double stopLoss;
    private double target;
    private double riskPoints;
    private double rewardPoints;

    // ── Outcome ──────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    private BacktestTrade.Outcome outcome;

    private double exitPrice;
    private double pnlPoints;
    private double pnlPercent;
    private double actualRR;

    @Column(name = "exit_candle_time")
    private LocalDateTime exitCandleTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ── Index enum ───────────────────────────────────────────────────────

    public enum IndexName {
        NIFTY_50("NSE_INDEX|Nifty 50",         "Nifty 50"),
        BANK_NIFTY("NSE_INDEX|Nifty Bank",      "Bank Nifty"),
        FIN_NIFTY("NSE_INDEX|Nifty Fin Service","Fin Nifty");

        public final String instrumentKey;
        public final String displayName;

        IndexName(String instrumentKey, String displayName) {
            this.instrumentKey = instrumentKey;
            this.displayName   = displayName;
        }
    }
}