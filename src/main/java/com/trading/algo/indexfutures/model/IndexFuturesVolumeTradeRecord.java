package com.trading.algo.indexfutures.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Single trade lifecycle record for the Index Futures Volume Spike backtest.
 */
@Data
@Builder
public class IndexFuturesVolumeTradeRecord {

    public enum Direction  { LONG, SHORT }
    public enum SignalType { BREAKOUT, ABSORPTION, CLIMAX }
    public enum ExitReason { FULL_TARGET, STOP_LOSS, OPEN }

    // ---- Instrument ----
    private String        label;           // e.g. "NIFTY FUT"
    private String        instrumentKey;

    // ---- Signal ----
    private SignalType    signalType;
    private Direction     direction;
    private LocalDateTime signalCandleTime;
    private double        volumeRatio;     // currentVolume / avgVolume
    private long          signalVolume;
    private long          avgVolume;

    // ---- Candle data at signal ----
    private double        signalOpen;
    private double        signalHigh;
    private double        signalLow;
    private double        signalClose;

    // ---- Trade levels ----
    private double        entry;
    private double        stopLoss;
    private double        target;
    private double        riskPoints;      // |entry - stopLoss|
    private double        rewardPoints;    // |target - entry|

    // ---- Execution ----
    private LocalDateTime exitTime;
    private ExitReason    exitReason;
    private double        exitPrice;

    /** P&L in R-multiples. Win = +RR, Loss = -1.0 */
    private double        pnlR;
}
