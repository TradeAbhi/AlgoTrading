package com.trading.algo.consolidation;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated backtest result for the Consolidation Breakout strategy.
 */
@Data
@Builder
public class ConsolidationBacktestResult {

    // ── Run parameters ───────────────────────────────────────────────────────
    private String    label;
    private String    timeframe;       // "15m" or "5m"
    private LocalDate fromDate;
    private LocalDate toDate;
    private double    maxRangePct;     // consolidation tightness threshold used
    private int       lookbackCandles; // N candles used for zone detection
    private double    targetRR;        // R multiple used for target

    // Confirmation filter — how far close must be beyond zone boundary as % of zone width.
    //
    // WHY DIFFERENT DEFAULTS PER TIMEFRAME:
    //   15-min zones are wider (40–60 pts on Nifty). 15% of a 50pt zone = 7.5pts extra
    //   confirmation which is too aggressive — filters out legitimate breakouts.
    //   A smaller value (5%) = ~2.5pts on a 50pt zone, just enough to avoid edge touches.
    //
    //   5-min zones are tighter (15–30 pts). 15% of a 20pt zone = 3pts which is
    //   proportionate and meaningfully filters marginal closes.
    //
    // confirmPct = 0.0  → no filter (original behaviour)
    // confirmPct = 0.05 → recommended for 15m (5% of zone width beyond boundary)
    // confirmPct = 0.15 → recommended for 5m  (15% of zone width beyond boundary)
    private double    confirmPct;

    // ── Overall stats ────────────────────────────────────────────────────────
    private int    totalTrades;
    private int    wins;               // TARGET_HIT
    private int    losses;             // SL_HIT
    private int    eodExits;           // EOD_EXIT (neither target nor SL hit by 3:15 PM)
    private int    pdcFilteredTrades;  // trades skipped because PDC was between entry and target
    private double winRate;            // wins / (wins + losses) × 100  [EOD excluded]
    private double totalPnlR;
    private double avgPnlR;
    private double profitFactor;       // gross profit R / gross loss R
    private double maxDrawdownR;
    private double avgWinR;
    private double avgLossR;
    private double largestWinR;
    private double largestLossR;
    private int    maxConsecWins;
    private int    maxConsecLosses;

    // ── Per-trade detail ─────────────────────────────────────────────────────
    private List<ConsolidationTradeRecord> trades;
}
