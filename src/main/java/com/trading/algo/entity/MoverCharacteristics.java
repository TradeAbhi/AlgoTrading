package com.trading.algo.entity;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Stores end-of-day characteristics of stocks that appeared in
 * Top Gainers, Top Losers, Volume Shockers, or Active by Value.
 *
 * Accumulated over days to identify patterns — e.g. "all top gainers
 * had volumeRatio > 50x and prevRsi between 50-65".
 */
@Entity
@Table(
        name = "mover_characteristics",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"symbol", "trade_date", "category"}
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoverCharacteristics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String    symbol;
    private LocalDate tradeDate;

    /** TOP_GAINER / TOP_LOSER / VOLUME_SHOCKER / ACTIVE_BY_VALUE */
    private String    category;

    // ── EOD price action ──────────────────────────────────────────────────────
    private double changePercent;      // day's % change
    private double openPrice;          // day open
    private double highPrice;          // day high
    private double lowPrice;           // day low
    private double closePrice;         // day close (LTP at EOD)
    private double gapPercent;         // (open - prevClose) / prevClose * 100

    // ── Volume ────────────────────────────────────────────────────────────────
    private double volumeRatio;        // today volume / 20-day avg volume
    @Column(name = "traded_value_cr")
    private double tradedValueCr;      // traded value in crores

    // ── Opening candle (9:15) characteristics ─────────────────────────────────
    private double c1WickRatio;        // C1 body / C1 range (0-1)
    private double c1BodyPct;          // C1 body / C1 open * 100
    private double c1RangePct;         // C1 range / C1 open * 100
    private long   c1Volume;           // C1 candle volume

    // ── Technical indicators (calculated from 20-day history) ─────────────────
    @Column(name = "prev_rsi")
    private Double prevRsi;            // RSI(14) at previous day close

    @Column(name = "atr_ratio")
    private Double atrRatio;           // today range / 14-day ATR (expansion factor)

    @Column(name = "above_20dma")
    private Boolean above20Dma;        // was closing price above 20-day moving average?

    @Column(name = "prev_day_change_pct")
    private Double prevDayChangePct;   // previous day's % change (momentum carry)

    // ── Metadata ──────────────────────────────────────────────────────────────
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}