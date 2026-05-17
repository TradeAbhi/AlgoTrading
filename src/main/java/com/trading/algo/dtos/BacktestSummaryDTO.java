package com.trading.algo.dtos;

import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated summary of the backtest run.
 * Returned by GET /api/backtest/summary
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestSummaryDTO {

    private LocalDate fromDate;
    private LocalDate toDate;

    private int  totalSignals;       // total trades found
    private int  totalWins;          // TARGET_HIT
    private int  totalLosses;        // SL_HIT
    private int  totalEodExits;      // EOD_EXIT (neither target nor SL)

    private double winRate;          // totalWins / totalSignals * 100
    private double avgWinPoints;
    private double avgLossPoints;
    private double avgRR;            // average actual risk:reward on closed trades
    private int    totalBreakevenExits;  // stopped at breakeven after 1.5R hit (Point 7)
    private int    totalHighVolumeSignals;     // C1 volume >= 1.5× C2 volume 🔥 (Point 2)
    private double totalPnlPoints;
    private double totalPnlRupees;    // sum of pnlRupees (qty x pnlPoints) — stocks only
    private double avgRiskRupees;     // average actual risk per trade in rupees
    private double expectancy;       // (winRate * avgWin) - (lossRate * avgLoss)

    private int  totalSymbolsScanned;
    private int  totalSymbolsWithSignal;

    /** Top 10 symbols by total P&L */
    private List<SymbolStat> topSymbols;

    /** Bottom 10 symbols by total P&L */
    private List<SymbolStat> worstSymbols;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SymbolStat {
        private String symbol;
        private int    totalTrades;
        private int    wins;
        private double winRate;
        private double totalPnlPoints;
    }
}