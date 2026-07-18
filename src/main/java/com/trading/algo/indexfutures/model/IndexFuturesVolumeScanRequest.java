package com.trading.algo.indexfutures.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Parameters for the Index Futures Volume Spike backtest / live scan.
 *
 * Instrument keys (Upstox):
 *   Nifty 50 index  : NSE_INDEX|Nifty 50
 *   Bank Nifty index: NSE_INDEX|Nifty Bank
 *
 * For live futures trading replace with the active monthly contract key, e.g.:
 *   NSE_FO|NIFTY25JUNFUT
 *   NSE_FO|BANKNIFTY25JUNFUT
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexFuturesVolumeScanRequest {

    /** Upstox instrument key */
    private String instrumentKey;

    /** Human-readable label shown in alerts, e.g. "NIFTY FUT" */
    private String label;

    private LocalDate fromDate;
    private LocalDate toDate;

    /** Volume >= spikeMultiplier x 20-candle avg -> qualifies as a spike */
    @Builder.Default
    private double spikeMultiplier = 2.0;

    /** Volume >= climaxMultiplier x 20-candle avg + 5-candle trend -> CLIMAX */
    @Builder.Default
    private double climaxMultiplier = 3.0;

    /** Risk per trade as % of entry price */
    @Builder.Default
    private double riskPercent = 0.5;

    /** Target R:R for BREAKOUT trades */
    @Builder.Default
    private double breakoutRR = 3.0;

    /** Target R:R for ABSORPTION trades (reversal after confirmation candle) */
    @Builder.Default
    private double absorptionRR = 2.0;

    /** Target R:R for CLIMAX trades (fade the exhaustion) */
    @Builder.Default
    private double climaxRR = 2.0;

    /** Extra SL buffer % beyond candle extreme to avoid noise stops */
    @Builder.Default
    private double slMarginPct = 0.1;

    /**
     * Only take signals at or after this hour (24h).
     * Default 9 = allow from 9:15 AM. Set to 10 to skip volatile open.
     */
    @Builder.Default
    private int signalAfterHour = 9;

    /**
     * Reject signals at or after this hour.
     * Default 15 = no trades at 3:00 PM or later.
     */
    @Builder.Default
    private int signalBeforeHour = 15;
}
