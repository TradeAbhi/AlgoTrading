package com.trading.algo.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Represents an extreme day (very positive or very negative) for Nifty index.
 * A day is considered extreme if the move is > 2 × ATR(14)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NiftyExtremeDay {

    private LocalDate date;
    private double open;
    private double close;
    private double high;
    private double low;
    private long volume;

    // ATR and move calculations
    private double atr14;
    private double dayMove;              // abs(close - open)
    private double atrMultiple;          // dayMove / atr14
    private String moveType;             // "POSITIVE" or "NEGATIVE"
    private double movePercentage;       // (dayMove / open) * 100
    private String niftyLevel;           // Description of the level at that time
}

