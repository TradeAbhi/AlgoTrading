package com.trading.algo.crypto;

import com.trading.algo.crypto.CryptoStrategyConfig;
import com.trading.algo.delta.service.DeltaApiService;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.telegram.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Backtest service for EMA+RSI+ATR Crypto Strategy
 *
 * Loads historical candle data from CSV files and runs the strategy
 * across multiple crypto assets (BTC/ETH/SOL)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoBacktestService {


    private final EmaRsiAtrStrategyService strategyService;
    private final CryptoStrategyConfig config;
    private final TelegramService telegramService;
    private final DeltaApiService deltaApiService;

    private static final Set<String> DEFAULT_SYMBOLS = Set.of("BTCUSD", "ETHUSD", "SOLUSD");

    /**
     * Run backtest on multiple crypto assets from Delta Exchange API
     *
     * @param symbols Set of crypto symbols (e.g., BTCUSD, ETHUSD, SOLUSD)
     * @param fromDate Start date for backtest
     * @param toDate End date for backtest
     * @param equity Starting equity for position sizing
     * @param requestId Unique request ID for tracking
     */
    @Async("backtestExecutor")
    public void runBacktestAsync(Set<String> symbols, LocalDate fromDate, LocalDate toDate, double equity, String requestId) {
        try {
            runBacktest(symbols, fromDate, toDate, equity, requestId);
        } catch (Exception e) {
            log.error("[Crypto-BT][{}] Backtest failed: {}", requestId, e.getMessage(), e);
            telegramService.sendMessage(String.format(
                "❌ *Crypto Backtest Failed*%n🆔 `%s`%n⚠️ %s", requestId, e.getMessage()));
        }
    }

    /**
     * Synchronous backtest execution
     */
    public CryptoBacktestSummary runBacktest(Set<String> symbols, LocalDate fromDate, LocalDate toDate, double equity, String requestId) {
        log.info("[Crypto-BT][{}] Starting backtest with {} symbols from {} to {}, equity=${}",
                requestId, symbols.size(), fromDate, toDate, equity);

        List<BacktestTrade> allTrades = new ArrayList<>();
        Map<String, List<Candle>> allCandles = new ConcurrentHashMap<>();

        // Collect trading days
        List<LocalDate> tradingDays = new ArrayList<>();
        LocalDate day = fromDate;
        while (!day.isAfter(toDate)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                tradingDays.add(day);
            }
            day = day.plusDays(1);
        }

        log.info("[Crypto-BT][{}] Trading days: {}", requestId, tradingDays.size());

        // Fetch candles from Delta API for each symbol
        for (String symbol : symbols) {
            try {
                List<Candle> candles = fetchCandlesFromDelta(symbol, fromDate, toDate);
                if (!candles.isEmpty()) {
                    allCandles.put(symbol, candles);
                    log.info("[Crypto-BT][{}] Loaded {} candles for {}", requestId, candles.size(), symbol);
                } else {
                    log.warn("[Crypto-BT][{}] No candles loaded for {}", requestId, symbol);
                }
            } catch (Exception e) {
                log.error("[Crypto-BT][{}] Failed to load candles for {}: {}", requestId, symbol, e.getMessage());
            }
        }

        // Run strategy on each symbol
        for (Map.Entry<String, List<Candle>> entry : allCandles.entrySet()) {
            String symbol = entry.getKey();
            List<Candle> candles = entry.getValue();

            double dailyPnL = 0.0;
            LocalDate currentDate = null;

            // Group candles by date and run strategy day by day
            Map<LocalDate, List<Candle>> candlesByDate = candles.stream()
                    .collect(Collectors.groupingBy(c -> c.getTimestamp().toLocalDate()));

            for (Map.Entry<LocalDate, List<Candle>> dateEntry : candlesByDate.entrySet()) {
                LocalDate date = dateEntry.getKey();
                List<Candle> dayCandles = dateEntry.getValue();

                // Reset daily P&L if date changed
                if (!date.equals(currentDate)) {
                    dailyPnL = 0.0;
                    currentDate = date;
                }

                try {
                    var trade = strategyService.evaluate(symbol, date, dayCandles, equity, dailyPnL);
                    if (trade.isPresent()) {
                        allTrades.add(trade.get());
                        dailyPnL += trade.get().getPnlRupees();

                        log.info("[Crypto-BT][{}] Trade: {} {} {} P&L={}",
                                requestId, symbol, date, trade.get().getDirection(),
                                trade.get().getPnlRupees());
                    }
                } catch (Exception e) {
                    log.error("[Crypto-BT][{}] Error evaluating {} on {}: {}",
                            requestId, symbol, date, e.getMessage());
                }
            }
        }

        // Build summary
        CryptoBacktestSummary summary = buildSummary(allTrades, equity, symbols);

        // Send results to Telegram
        sendResultsToTelegram(summary, allTrades, requestId);

        return summary;
    }

    /**
     * Fetch candles from Delta Exchange API
     */
    private List<Candle> fetchCandlesFromDelta(String symbol, LocalDate fromDate, LocalDate toDate) {
        List<Candle> candles = new ArrayList<>();

        try {
            ZonedDateTime startDateTime = fromDate.atStartOfDay(ZoneOffset.UTC);
            ZonedDateTime endDateTime = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC);

            long startEpoch = startDateTime.toEpochSecond();
            long endEpoch = endDateTime.toEpochSecond();

            // Fetch 15-minute candles from Delta API
            List<com.trading.algo.delta.model.Candle> deltaCandles = deltaApiService.get15mCandles(symbol, startEpoch, endEpoch);

            // Convert Delta candles to our Candle DTO format
            for (com.trading.algo.delta.model.Candle dc : deltaCandles) {
                candles.add(Candle.builder()
                        .timestamp(dc.getOpenTime().atZone(ZoneOffset.UTC).toLocalDateTime())
                        .open(dc.getOpen().doubleValue())
                        .high(dc.getHigh().doubleValue())
                        .low(dc.getLow().doubleValue())
                        .close(dc.getClose().doubleValue())
                        .volume(dc.getVolume().longValue())
                        .build());
            }

            log.debug("Fetched {} candles for {} from Delta API", candles.size(), symbol);
        } catch (Exception e) {
            log.error("Failed to fetch candles for {} from Delta API: {}", symbol, e.getMessage());
        }
        
        return candles;
    }

    /**
     * Build backtest summary statistics
     */
    private CryptoBacktestSummary buildSummary(List<BacktestTrade> trades, double equity, java.util.Set<String> symbols) {
        if (trades.isEmpty()) {
            return new CryptoBacktestSummary(equity, symbols, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        long wins = trades.stream().filter(t -> t.getPnlRupees() > 0).count();
        long losses = trades.stream().filter(t -> t.getPnlRupees() <= 0).count();
        double totalPnl = trades.stream().mapToDouble(BacktestTrade::getPnlRupees).sum();
        double avgPnl = totalPnl / trades.size();
        double bestTrade = trades.stream().mapToDouble(BacktestTrade::getPnlRupees).max().orElse(0);
        double worstTrade = trades.stream().mapToDouble(BacktestTrade::getPnlRupees).min().orElse(0);
        double winRate = (wins * 100.0) / trades.size();

        // Calculate profit factor
        double grossProfit = trades.stream().filter(t -> t.getPnlRupees() > 0)
                .mapToDouble(BacktestTrade::getPnlRupees).sum();
        double grossLoss = Math.abs(trades.stream().filter(t -> t.getPnlRupees() < 0)
                .mapToDouble(BacktestTrade::getPnlRupees).sum());
        double profitFactor = grossLoss == 0 ? grossProfit > 0 ? Double.MAX_VALUE : 0 : grossProfit / grossLoss;

        // Calculate max drawdown
        double maxDrawdown = 0, runningPnl = 0, peakPnl = 0;
        for (BacktestTrade t : trades) {
            runningPnl += t.getPnlRupees();
            if (runningPnl > peakPnl) peakPnl = runningPnl;
            double dd = peakPnl - runningPnl;
            if (dd > maxDrawdown) maxDrawdown = dd;
        }

        double totalReturn = (totalPnl / equity) * 100.0;

        log.info("[Crypto-BT] Summary: {} trades | WR: {}% | Total Return: {}% | Profit Factor: {}",
                trades.size(), String.format("%.1f", winRate), 
                String.format("%.2f", totalReturn), String.format("%.2f", profitFactor));

        return new CryptoBacktestSummary(equity, symbols, trades.size(), 
                (int) wins, (int) losses, winRate, totalReturn, 
                avgPnl, bestTrade, worstTrade, profitFactor, maxDrawdown);
    }

    /**
     * Send backtest results to Telegram
     */
    private void sendResultsToTelegram(CryptoBacktestSummary summary, List<BacktestTrade> trades, String requestId) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Crypto Strategy Backtest Results*\n");
        sb.append("🆔 `").append(requestId).append("`\n");
        sb.append("💰 Starting Equity: $").append(String.format("%.2f", summary.getEquity())).append("\n");
        sb.append("🪙 Assets: ").append(String.join(", ", summary.getSymbols())).append("\n");
        sb.append("────────────────\n");
        sb.append("📋 Total Trades:  ").append(summary.getTotalTrades()).append("\n");
        sb.append("✅ Wins:          ").append(summary.getWins()).append("\n");
        sb.append("❌ Losses:        ").append(summary.getLosses()).append("\n");
        sb.append("🎯 Win Rate:      ").append(String.format("%.1f", summary.getWinRate())).append("%\n");
        sb.append("💰 Total Return:  ").append(String.format("%.2f", summary.getTotalReturn())).append("%\n");
        sb.append("📈 Avg P&L:       $").append(String.format("%.2f", summary.getAvgPnl())).append("\n");
        sb.append("🚀 Best Trade:    $").append(String.format("%.2f", summary.getBestTrade())).append("\n");
        sb.append("💥 Worst Trade:   $").append(String.format("%.2f", summary.getWorstTrade())).append("\n");
        sb.append("📊 Profit Factor: ").append(String.format("%.2f", summary.getProfitFactor())).append("\n");
        sb.append("📉 Max Drawdown:  $").append(String.format("%.2f", summary.getMaxDrawdown())).append("\n");

        telegramService.sendMessage(sb.toString());

        // Send CSV with trade details
        byte[] csv = buildCsv(trades);
        String fileName = String.format("crypto_backtest_%s.csv", requestId.substring(0, 8));
        telegramService.sendDocument(csv, fileName,
                String.format("Crypto Strategy Backtest | %d trades | ID: `%s`", trades.size(), requestId));
    }

    /**
     * Build CSV with trade details
     */
    private byte[] buildCsv(List<BacktestTrade> trades) {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol,Date,Direction,Entry,Stop Loss,Target,Exit,Risk Points,Reward Points,Quantity,P&L ($),P&L (%),Outcome,Actual RR\n");
        
        for (BacktestTrade t : trades) {
            sb.append(String.format("%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%.2f,%.2f,%s,%.2f\n",
                    t.getSymbol(),
                    t.getTradeDate(),
                    t.getDirection(),
                    t.getEntryPrice(),
                    t.getStopLoss(),
                    t.getTarget(),
                    t.getExitPrice(),
                    t.getRiskPoints(),
                    t.getRewardPoints(),
                    t.getQuantity(),
                    t.getPnlRupees(),
                    t.getPnlPercent(),
                    t.getOutcome(),
                    t.getActualRR()));
        }
        
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Summary data class for crypto backtest results
     */
    public static class CryptoBacktestSummary {
        private final double equity;
        private final java.util.Set<String> symbols;
        private final int totalTrades;
        private final int wins;
        private final int losses;
        private final double winRate;
        private final double totalReturn;
        private final double avgPnl;
        private final double bestTrade;
        private final double worstTrade;
        private final double profitFactor;
        private final double maxDrawdown;

        public CryptoBacktestSummary(double equity, java.util.Set<String> symbols, 
                                    int totalTrades, int wins, int losses,
                                    double winRate, double totalReturn, double avgPnl,
                                    double bestTrade, double worstTrade, 
                                    double profitFactor, double maxDrawdown) {
            this.equity = equity;
            this.symbols = symbols;
            this.totalTrades = totalTrades;
            this.wins = wins;
            this.losses = losses;
            this.winRate = winRate;
            this.totalReturn = totalReturn;
            this.avgPnl = avgPnl;
            this.bestTrade = bestTrade;
            this.worstTrade = worstTrade;
            this.profitFactor = profitFactor;
            this.maxDrawdown = maxDrawdown;
        }

        public double getEquity() { return equity; }
        public java.util.Set<String> getSymbols() { return symbols; }
        public int getTotalTrades() { return totalTrades; }
        public int getWins() { return wins; }
        public int getLosses() { return losses; }
        public double getWinRate() { return winRate; }
        public double getTotalReturn() { return totalReturn; }
        public double getAvgPnl() { return avgPnl; }
        public double getBestTrade() { return bestTrade; }
        public double getWorstTrade() { return worstTrade; }
        public double getProfitFactor() { return profitFactor; }
        public double getMaxDrawdown() { return maxDrawdown; }
    }
}
