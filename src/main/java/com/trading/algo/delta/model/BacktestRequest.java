package com.trading.algo.delta.model;


import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
    @Builder.Default
    private double slMarginPct = 0.15;

    /**
     * Risk:Reward ratio for the full target.
     * Default: 3.0 → Target = Entry - (3 × risk)   [for short]
     */
    @Builder.Default
    private double riskRewardRatio = 3.0;

    /**
     * R:R level at which to (a) book half quantity and (b) trail SL to entry.
     * Default: 2.0
     */
    @Builder.Default
    private double partialExitRR = 2.0;

    /**
     * Percentage of the original position to close at partialExitRR.
     * Default: 50 (%)
     */
    @Builder.Default
    private double partialExitQtyPct = 50.0;
}