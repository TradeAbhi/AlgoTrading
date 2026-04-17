package com.trading.algo.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TradingDashboardDTO {

    private double totalPnl;
    private int totalTradingDays;
    private long winningDays;
    private long losingDays;
    private double winRate;

    private double averageProfit;
    private double averageLoss;

    private double bestDayPnl;
    private double worstDayPnl;

    private int maxWinningStreak;
    private int maxLosingStreak;
}
