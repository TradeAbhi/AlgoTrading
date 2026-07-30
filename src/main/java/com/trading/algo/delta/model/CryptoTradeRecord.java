package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents a trade record from crypto consolidation backtest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoTradeRecord {
    
    public enum Direction {
        BULLISH,
        BEARISH
    }
    
    public enum Timeframe {
        MINUTES_15,
        DAILY
    }
    
    public enum ExitReason {
        TARGET_HIT,
        SL_HIT,
        EOD_EXIT
    }
    
    private String symbol;
    private Direction direction;
    private Timeframe timeframe;
    private LocalDate tradeDate;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    
    // Consolidation zone details
    private LocalDate zoneStartDate;
    private LocalDate zoneEndDate;
    private BigDecimal zoneHigh;
    private BigDecimal zoneLow;
    private BigDecimal zoneWidthPct;
    private int consolidationDays;
    
    // Trade details
    private BigDecimal entryPrice;
    private BigDecimal stopLoss;
    private BigDecimal target;
    private BigDecimal risk;
    private BigDecimal exitPrice;
    private ExitReason exitReason;
    
    // P&L
    private BigDecimal pnlPoints;
    private BigDecimal pnlR;
    
    /**
     * Calculates the risk-reward ratio for this trade.
     */
    public BigDecimal getRiskRewardRatio() {
        if (risk == null || risk.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal reward = direction == Direction.BULLISH 
            ? target.subtract(entryPrice)
            : entryPrice.subtract(target);
        return reward.divide(risk, 2, java.math.RoundingMode.HALF_UP);
    }
}
