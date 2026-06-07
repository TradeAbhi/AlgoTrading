package com.trading.algo.delta.model;


import java.time.LocalDate;

import lombok.Data;

/**
 * Parameters for a backtest run, supplied via the REST API.
 *
 * Example JSON:
 * {
 *   "symbol"       : "BTCUSD",
 *   "fromDate"     : "2024-01-01",
 *   "toDate"       : "2024-06-30",
 *   "slMarginPct"  : 0.15,
 *   "riskRewardRatio": 3.0,
 *   "partialExitRR"  : 2.0,
 *   "partialExitQtyPct": 50.0
 * }
 */
@Data
public class BacktestRequest {

    /** Delta product symbol, e.g. BTCUSD */
    private String symbol;

    /** Start date of backtest window (inclusive, UTC) */
    private LocalDate fromDate;

    /** End date of backtest window (inclusive, UTC) */
    private LocalDate toDate;

    /**
     * Extra margin added to the SL level above the high of the
     * 2nd candle prior to the breakdown candle, expressed as %.
     * Default: 0.15 (i.e. 0.15%)
     */
    private double slMarginPct = 0.15;

    /**
     * Risk:Reward ratio for the full target.
     * Default: 3.0 → Target = Entry - (3 × risk)   [for short]
     */
    private double riskRewardRatio = 3.0;

    /**
     * R:R level at which to (a) book half quantity and (b) trail SL to entry.
     * Default: 2.0
     */
    private double partialExitRR = 2.0;

    /**
     * Percentage of the original position to close at partialExitRR.
     * Default: 50 (%)
     */
    private double partialExitQtyPct = 50.0;
}