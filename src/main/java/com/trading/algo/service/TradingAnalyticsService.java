package com.trading.algo.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.trading.algo.dtos.TradingDashboardDTO;
import com.trading.algo.entity.DailyTradingSummary;
import com.trading.algo.repo.DailyTradingSummaryRepository;

@Service
public class TradingAnalyticsService {

    @Autowired
    private DailyTradingSummaryRepository repository;

    public void processCsv(MultipartFile file) throws Exception {

        Map<LocalDate, List<Double>> pnlMap = new HashMap<>();
        Map<LocalDate, Integer> tradeCountMap = new HashMap<>();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream())
        );

        String line;
        boolean header = true;

        while ((line = reader.readLine()) != null) {

            if (header) { header = false; continue; }

            String[] cols = line.split(",");

            // Adjust index based on CSV format
            LocalDate date = LocalDate.parse(cols[2]); // ✅ works directly
            //double pnl = Double.parseDouble(cols[5]); 
            double pnl = 0; 

            pnlMap.computeIfAbsent(date, k -> new ArrayList<>()).add(pnl);
            tradeCountMap.put(date, tradeCountMap.getOrDefault(date, 0) + 1);
        }

        // Aggregate per day
        for (LocalDate date : pnlMap.keySet()) {

            double totalPnl = pnlMap.get(date)
                    .stream().mapToDouble(Double::doubleValue).sum();

            int trades = tradeCountMap.get(date);

            String result = totalPnl > 0 ? "WIN" : "LOSS";

            DailyTradingSummary summary = new DailyTradingSummary();
            summary.setTradingDate(date);
            summary.setNumberOfTrades(trades);
            summary.setTotalPnl(totalPnl);
            summary.setResult(result);

            repository.save(summary);
        }
    }

    public List<DailyTradingSummary> getMonthlySummary() {
        YearMonth month = YearMonth.now();

        return repository.findByTradingDateBetween(
                month.atDay(1),
                month.atEndOfMonth()
        );
    }

	public List<DailyTradingSummary> findAll() {
		return repository.findAll();
	}

	public List<DailyTradingSummary> getMonthlySummary(int year, int month) {

	    YearMonth ym = YearMonth.of(year, month);

	    return repository.findByTradingDateBetween(
	            ym.atDay(1),
	            ym.atEndOfMonth()
	    );
	}
	
	public TradingDashboardDTO getDashboard(int year, int month) {

        YearMonth ym = YearMonth.of(year, month);

        List<DailyTradingSummary> data =
                repository.findByTradingDateBetween(
                        ym.atDay(1),
                        ym.atEndOfMonth()
                );

        TradingDashboardDTO dto = new TradingDashboardDTO();

        if (data.isEmpty()) return dto;

        double totalPnl = 0;
        int totalDays = data.size();

        long winDays = 0;
        long lossDays = 0;

        double totalProfit = 0;
        double totalLoss = 0;

        double bestDay = Double.MIN_VALUE;
        double worstDay = Double.MAX_VALUE;

        int currentWinStreak = 0;
        int currentLossStreak = 0;
        int maxWinStreak = 0;
        int maxLossStreak = 0;

        for (DailyTradingSummary d : data) {

            double pnl = d.getTotalPnl();
            totalPnl += pnl;

            if (pnl > 0) {
                winDays++;
                totalProfit += pnl;

                currentWinStreak++;
                currentLossStreak = 0;

            } else {
                lossDays++;
                totalLoss += pnl;

                currentLossStreak++;
                currentWinStreak = 0;
            }

            maxWinStreak = Math.max(maxWinStreak, currentWinStreak);
            maxLossStreak = Math.max(maxLossStreak, currentLossStreak);

            bestDay = Math.max(bestDay, pnl);
            worstDay = Math.min(worstDay, pnl);
        }

        dto.setTotalPnl(totalPnl);
        dto.setTotalTradingDays(totalDays);
        dto.setWinningDays(winDays);
        dto.setLosingDays(lossDays);

        dto.setWinRate((winDays * 100.0) / totalDays);

        dto.setAverageProfit(winDays == 0 ? 0 : totalProfit / winDays);
        dto.setAverageLoss(lossDays == 0 ? 0 : totalLoss / lossDays);

        dto.setBestDayPnl(bestDay);
        dto.setWorstDayPnl(worstDay);

        dto.setMaxWinningStreak(maxWinStreak);
        dto.setMaxLosingStreak(maxLossStreak);

        return dto;
    }
	
	public void processPnlCsv(MultipartFile file) throws Exception {

	    BufferedReader reader = new BufferedReader(
	            new InputStreamReader(file.getInputStream())
	    );

	    String line;

	    boolean headerFound = false;

	    Map<LocalDate, Double> pnlPerDay = new HashMap<>();
	    Map<LocalDate, Integer> tradeCount = new HashMap<>();

	    while ((line = reader.readLine()) != null) {

	        // STEP 1: Find header
	        if (!headerFound) {
	            if (line.contains("Symbol") && line.contains("Realized")) {
	                headerFound = true;
	            }
	            continue;
	        }

	        // STEP 2: Skip empty lines
	        if (line.trim().isEmpty()) continue;

	        String[] cols = line.split(",");

	        // ⚠️ IMPORTANT: Adjust index based on your file
	        // From your screenshot:
	        // Symbol | ISIN | Quantity | Buy Value | Sell Value | Realized P&L

	        String pnlStr = cols[5]; // Realized P&L

	        // ⚠️ This file DOES NOT have trade_date directly
	        // So we cannot group by date here

	        double pnl = Double.parseDouble(pnlStr);

	        // For now store as single summary (no date split)
	        LocalDate today = LocalDate.now();

	        pnlPerDay.put(today, pnlPerDay.getOrDefault(today, 0.0) + pnl);
	        tradeCount.put(today, tradeCount.getOrDefault(today, 0) + 1);
	    }

	    // Save
	    for (LocalDate date : pnlPerDay.keySet()) {

	        DailyTradingSummary summary = new DailyTradingSummary();

	        summary.setTradingDate(date);
	        summary.setTotalPnl(pnlPerDay.get(date));
	        summary.setNumberOfTrades(tradeCount.get(date));
	        summary.setResult(pnlPerDay.get(date) > 0 ? "WIN" : "LOSS");

	        repository.save(summary);
	    }
	}
}