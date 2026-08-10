package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Trade record for Previous Day Strong Candle Breakout Strategy.
 *
 * Strategy:
 * - Previous day's candle body >= 65%
 * - Bullish candle -> Buy only above previous day high
 * - Bearish candle -> Sell only below previous day low
 * - Initial SL = Breakout candle +/- 0.5%
 * - Partial exit:
 *      60% at 3R
 *      Remaining 40% at 4R (after SL moves to breakeven)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoStrongCandleTradeRecord {

    public enum Direction {
        BULLISH,
        BEARISH
    }

    public enum ExitReason {
        STOP_LOSS,
        BREAK_EVEN,
        TARGET_3R_ONLY,
        TARGET_4R,
        END_OF_DATA
    }

    private String symbol;

    private LocalDate tradeDate;

    private Direction direction;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;

    // -------------------------
    // Previous Day Candle
    // -------------------------

    private BigDecimal previousDayOpen;

    private BigDecimal previousDayHigh;

    private BigDecimal previousDayLow;

    private BigDecimal previousDayClose;

    /**
     * abs(close-open)/(high-low)
     */
    private BigDecimal bodyRatio;

    // -------------------------
    // Breakout Candle
    // -------------------------

    private BigDecimal breakoutHigh;

    private BigDecimal breakoutLow;

    // -------------------------
    // Trade
    // -------------------------

    private BigDecimal entryPrice;

    private BigDecimal stopLoss;

    /**
     * Initial risk in points.
     */
    private BigDecimal risk;

    /**
     * 3R target.
     */
    private BigDecimal target3R;

    /**
     * 4R target.
     */
    private BigDecimal target4R;

    // -------------------------
    // Partial Exit
    // -------------------------

    /**
     * Whether 60% booking happened.
     */
    private boolean partialBooked;

    /**
     * Price where 60% exited.
     */
    private BigDecimal partialExitPrice;

    /**
     * Final exit price.
     */
    private BigDecimal finalExitPrice;

    /**
     * Remaining position exited because of
     * 4R / BE / SL / EOD.
     */
    private ExitReason exitReason;

    // -------------------------
    // Performance
    // -------------------------

    /**
     * Weighted total PnL considering
     * 60% booked at 3R and
     * 40% booked at final exit.
     */
    private BigDecimal pnlPoints;

    /**
     * Overall R multiple.
     */
    private BigDecimal pnlR;

    /**
     * Maximum favorable excursion.
     */
    private BigDecimal mfe;

    /**
     * Maximum adverse excursion.
     */
    private BigDecimal mae;

    /**
     * Trade duration.
     */
    private Integer candlesHeld;

    /**
     * True if SL shifted to entry.
     */
    private boolean breakEvenActivated;

    private int reEntrySequence;                 // 0 = primary entry, 1,2,3... = Obs-5 chained re-entry
    private BigDecimal positionSizeMultiplier;    // Obs-3: 1.0 normally, 0.5 when consecutive pre-move detected
    private boolean consecutiveMoveDetected;      // Obs-3 flag
    private boolean slTrailApplied;               // Obs-1 flag

    private BigDecimal atrValue;              // raw ATR(14) at entry
    private BigDecimal originalStopLoss;      // ATR-based SL, before any trail/breakeven
    private BigDecimal trailedStopLossLevel;  // Obs-1 result, null if trail never triggered
    private BigDecimal breakEvenStopLoss;     // = entry, null if breakeven never triggered
// stopLoss (existing field) stays as-is: the FINAL/active SL at exit
}