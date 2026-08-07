package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoStrongCandleBacktestReport {

    @Builder.Default
    private List<CryptoStrongCandleTradeRecord> trades = new ArrayList<>();

    private int totalTrades;

    private int winningTrades;

    private int losingTrades;

    private int breakEvenTrades;

    private BigDecimal grossProfit;

    private BigDecimal grossLoss;

    private BigDecimal netProfit;

    private BigDecimal averageWinner;

    private BigDecimal averageLoser;

    private BigDecimal profitFactor;

    private BigDecimal expectancy;

    private BigDecimal maxDrawdown;

    private BigDecimal winRate;

    private BigDecimal averageRR;

    public static CryptoStrongCandleBacktestReport fromTrades(
            List<CryptoStrongCandleTradeRecord> trades) {

        CryptoStrongCandleBacktestReport report =
                new CryptoStrongCandleBacktestReport();

        report.setTrades(trades);

        report.calculate();

        return report;
    }

    public void calculate() {

        totalTrades = trades.size();

        grossProfit = BigDecimal.ZERO;
        grossLoss = BigDecimal.ZERO;

        BigDecimal totalRR = BigDecimal.ZERO;

        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal drawdown = BigDecimal.ZERO;

        winningTrades = 0;
        losingTrades = 0;
        breakEvenTrades = 0;

        for (CryptoStrongCandleTradeRecord trade : trades) {

            BigDecimal pnl = trade.getPnlPoints();

            if (pnl == null) {
                continue;
            }

            if (pnl.compareTo(BigDecimal.ZERO) > 0) {

                winningTrades++;

                grossProfit = grossProfit.add(pnl);

            } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {

                losingTrades++;

                grossLoss = grossLoss.add(pnl.abs());

            } else {

                breakEvenTrades++;
            }

            if (trade.getPnlR() != null) {
                totalRR = totalRR.add(trade.getPnlR());
            }

            equity = equity.add(pnl);

            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }

            BigDecimal currentDD = peak.subtract(equity);

            if (currentDD.compareTo(drawdown) > 0) {
                drawdown = currentDD;
            }
        }

        maxDrawdown = drawdown;

        netProfit = grossProfit.subtract(grossLoss);

        if (winningTrades > 0) {
            averageWinner = grossProfit.divide(
                    BigDecimal.valueOf(winningTrades),
                    4,
                    RoundingMode.HALF_UP);
        } else {
            averageWinner = BigDecimal.ZERO;
        }

        if (losingTrades > 0) {
            averageLoser = grossLoss.divide(
                    BigDecimal.valueOf(losingTrades),
                    4,
                    RoundingMode.HALF_UP);
        } else {
            averageLoser = BigDecimal.ZERO;
        }

        if (grossLoss.compareTo(BigDecimal.ZERO) > 0) {

            profitFactor = grossProfit.divide(
                    grossLoss,
                    4,
                    RoundingMode.HALF_UP);

        } else {

            profitFactor = BigDecimal.ZERO;
        }

        if (totalTrades > 0) {

            winRate = BigDecimal.valueOf(winningTrades)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalTrades),
                            2,
                            RoundingMode.HALF_UP);

            averageRR = totalRR.divide(
                    BigDecimal.valueOf(totalTrades),
                    4,
                    RoundingMode.HALF_UP);

        } else {

            winRate = BigDecimal.ZERO;
            averageRR = BigDecimal.ZERO;
        }

        expectancy = averageRR;
    }

    @Override
    public String toString() {

        return "\n=============================="
                + "\nStrong Candle Backtest Report"
                + "\n=============================="
                + "\nTotal Trades   : " + totalTrades
                + "\nWins           : " + winningTrades
                + "\nLosses         : " + losingTrades
                + "\nBreak Even     : " + breakEvenTrades
                + "\nWin Rate       : " + winRate + "%"
                + "\nGross Profit   : " + grossProfit
                + "\nGross Loss     : " + grossLoss
                + "\nNet Profit     : " + netProfit
                + "\nProfit Factor  : " + profitFactor
                + "\nAverage Winner : " + averageWinner
                + "\nAverage Loser  : " + averageLoser
                + "\nAverage R      : " + averageRR
                + "\nExpectancy     : " + expectancy
                + "\nMax Drawdown   : " + maxDrawdown
                + "\n==============================";
    }

}