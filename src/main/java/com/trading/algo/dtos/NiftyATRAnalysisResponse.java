package com.trading.algo.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for Nifty ATR-based extreme day analysis.
 * Contains the most positive and most negative days, along with analysis details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NiftyATRAnalysisResponse {

    private LocalDate analysisStartDate;
    private LocalDate analysisEndDate;
    private int totalTradingDays;
    private int extremeDaysCount;

    // Analysis Threshold
    private double atrThreshold;  // 2.0 means > 2 × ATR(14)
    private int atrPeriod;        // Usually 14

    // Most Positive Day (largest Close - Open relative to ATR)
    private NiftyExtremeDay mostPositiveDay;

    // Most Negative Day (largest Open - Close relative to ATR)
    private NiftyExtremeDay mostNegativeDay;

    // All Positive Days (sorted by ATR multiple descending)
    private List<NiftyExtremeDay> allPositiveDays;

    // All Negative Days (sorted by ATR multiple descending)
    private List<NiftyExtremeDay> allNegativeDays;

    // Statistics
    private double averageAtr14;
    private double averageAtrMultiplePositive;
    private double averageAtrMultipleNegative;
    private double maxAtrMultiplePositive;
    private double maxAtrMultipleNegative;
}

