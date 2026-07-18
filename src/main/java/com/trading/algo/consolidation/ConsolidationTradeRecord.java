package com.trading.algo.consolidation;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Single trade record for the Consolidation Breakout backtest.
 */
@Data
@Builder
public class ConsolidationTradeRecord {

    public enum Direction  { BULLISH, BEARISH }
    public enum Timeframe  { TF_15M, TF_5M }
    public enum ExitReason { TARGET_HIT, SL_HIT, EOD_EXIT }

    // ── Signal info ──────────────────────────────────────────────────────────
    private String        label;
    private Timeframe     timeframe;
    private Direction     direction;
    private LocalDate     tradeDate;
    private LocalDateTime breakoutCandleTime;

    // ── Zone that was broken ─────────────────────────────────────────────────
    private double zoneHigh;
    private double zoneLow;
    private double zoneRangePct;       // (zoneHigh - zoneLow) / midpoint × 100

    // ── Trade levels ─────────────────────────────────────────────────────────
    private double entry;              // breakout candle close
    private double stopLoss;           // opposite side of zone
    private double target;             // entry ± (risk × targetRR)
    private double riskPoints;         // |entry - stopLoss|
    private double prevDayClose;       // previous day close — used for PDC filter
    private boolean pdcFiltered;       // true = trade was skipped due to PDC in path (backtest only)

    // ── Result ───────────────────────────────────────────────────────────────
    private ExitReason    exitReason;
    private double        exitPrice;
    private LocalDateTime exitTime;
    private double        pnlPoints;   // exitPrice - entry (BULLISH) or entry - exitPrice (BEARISH)
    private double        pnlR;        // pnlPoints / riskPoints
}
