package com.trading.algo.service;

import com.trading.algo.dtos.Candle;
import com.trading.algo.dtos.WatchlistItem;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.BacktestWinnerAnalysis;
import com.trading.algo.repo.BacktestTradeRepository;
import com.trading.algo.repo.BacktestWinnerAnalysisRepository;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generic service to analyze winning backtest trades and identify patterns
 * that caused the price movement.
 *
 * Pluggable into any backtest service (ORB, DELTA, FIBO, IPO, etc.)
 *
 * Winner criteria:
 *   - TARGET_HIT: Full target reached
 *   - BREAKEVEN_EXIT: Partial target hit, then SL moved to breakeven
 *   - EOD_EXIT with positive PnL: Exit at EOD but still profitable
 *
 * Analysis includes:
 *   - Volume patterns (volume ratio, C1 vs C2 volume)
 *   - RSI(14) before the trade
 *   - ATR ratio (today's range / 14-day ATR)
 *   - Gap analysis
 *   - 20-day moving average position
 *   - Previous day's change
 *   - Pattern flags (strong opening, breakout, high-volume breakout)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestWinnerAnalysisService {

    private static final int HISTORY_DAYS = 35;
    private static final int RSI_PERIOD = 14;
    private static final int PATTERN_DAYS = 10;

    private final BacktestTradeRepository backtestTradeRepository;
    private final BacktestWinnerAnalysisRepository analysisRepository;
    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final TelegramService telegramService;

    // =========================================================================
    // Main entry points
    // =========================================================================

    /**
     * Analyze winners from a specific strategy for a date range
     * @param strategyName Strategy identifier (e.g., "ORB", "DELTA", "FIBO", "IPO")
     * @param from Start date
     * @param to End date
     */
    @Transactional
    public void analyzeWinners(String strategyName, LocalDate from, LocalDate to) {
        log.info("Analyzing winners for strategy {} from {} to {}", strategyName, from, to);

        List<BacktestTrade> allTrades = backtestTradeRepository.findByTradeDateBetweenOrderByTradeDateAsc(from, to);
        List<BacktestTrade> winners = filterWinners(allTrades);

        log.info("Found {} winners out of {} total trades for {}", winners.size(), allTrades.size(), strategyName);

        List<BacktestWinnerAnalysis> saved = new ArrayList<>();
        for (BacktestTrade trade : winners) {
            try {
                // Skip if already analyzed
                if (analysisRepository.existsBySymbolAndTradeDateAndStrategyName(
                        trade.getSymbol(), trade.getTradeDate(), strategyName)) {
                    continue;
                }

                BacktestWinnerAnalysis analysis = analyzeTrade(trade, strategyName);
                if (analysis != null) {
                    analysisRepository.save(analysis);
                    saved.add(analysis);
                    log.info("Analyzed winner {} {} - PnL={}%, RSI={}, VolRatio={}",
                            strategyName, trade.getSymbol(), trade.getPnlPercent(),
                            analysis.getPrevRsi(), analysis.getVolumeRatio());
                }

                Thread.sleep(150); // rate limit

            } catch (Exception e) {
                log.error("Error analyzing trade {} {}: {}", strategyName, trade.getSymbol(), e.getMessage());
            }
        }

        log.info("Winner analysis complete for {} - {} records saved", strategyName, saved.size());

        // Send Telegram report
        sendTelegramReport(strategyName, from, to);
    }

    /**
     * Analyze today's winners (scheduled execution)
     */
    public void analyzeTodayWinners(String strategyName) {
        analyzeWinners(strategyName, LocalDate.now(), LocalDate.now());
    }

    /**
     * Scheduled execution at 4:00 PM for ORB strategy
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledAnalyzeOrbWinners() {
        analyzeTodayWinners("ORB");
    }

    /**
     * Scheduled execution at 4:05 PM for DELTA strategy
     */
    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledAnalyzeDeltaWinners() {
        analyzeTodayWinners("DELTA");
    }

    // =========================================================================
    // Winner filtering
    // =========================================================================

    /**
     * Filter trades to identify winners
     * Winners: TARGET_HIT, BREAKEVEN_EXIT, or EOD_EXIT with positive PnL
     */
    private List<BacktestTrade> filterWinners(List<BacktestTrade> trades) {
        List<BacktestTrade> winners = new ArrayList<>();
        for (BacktestTrade trade : trades) {
            if (isWinner(trade)) {
                winners.add(trade);
            }
        }
        return winners;
    }

    private boolean isWinner(BacktestTrade trade) {
        return switch (trade.getOutcome()) {
            case TARGET_HIT, BREAKEVEN_EXIT -> true;
            case EOD_EXIT -> trade.getPnlPercent() > 0;
            default -> false;
        };
    }

    // =========================================================================
    // Trade analysis
    // =========================================================================

    private BacktestWinnerAnalysis analyzeTrade(BacktestTrade trade, String strategyName) {
        String symbol = trade.getSymbol();
        LocalDate tradeDate = trade.getTradeDate();

        // Get instrument key
        var keyOpt = instrumentMaster.getInstrumentKey(symbol);
        if (keyOpt.isEmpty()) {
            log.debug("No instrument key for {}", symbol);
            return null;
        }
        String key = keyOpt.get();

        // Fetch historical data for indicators
        LocalDate histFrom = tradeDate.minusDays(HISTORY_DAYS);
        List<Candle> dailyCandles = fetchDailyCandles(key, histFrom, tradeDate.minusDays(1));

        // Fetch intraday candles for C1 analysis
        List<Candle> todayCandles = candleService.fetchDayCandles(key, tradeDate);
        Candle c1 = findC1Candle(todayCandles);

        // Calculate indicators
        double prevRsi = dailyCandles.size() >= RSI_PERIOD + 1 ? calculateRsi(dailyCandles) : 0;
        double atrRatio = dailyCandles.size() >= 15 ? calculateAtrRatio(dailyCandles, trade) : 0;
        boolean above20Dma = dailyCandles.size() >= 20 ? isAbove20Dma(dailyCandles, trade.getEntryPrice()) : false;
        double prevDayChg = dailyCandles.size() >= 2 ? calcPrevDayChange(dailyCandles) : 0;
        double volumeRatio = calculateVolumeRatio(dailyCandles, trade.getC1Volume());

        // Calculate gap (if we have previous close)
        double gapPercent = 0;
        if (!dailyCandles.isEmpty()) {
            Candle prevDay = dailyCandles.get(dailyCandles.size() - 1);
            if (prevDay.getClose() > 0) {
                gapPercent = (trade.getC1Open() - prevDay.getClose()) / prevDay.getClose() * 100;
            }
        }

        // C1 characteristics
        double c1WickRatio = trade.getC1WickRatio();
        double c1BodyPct = 0;
        double c1RangePct = 0;
        if (c1 != null && c1.getOpen() > 0) {
            c1BodyPct = c1.body() / c1.getOpen() * 100;
            c1RangePct = c1.range() / c1.getOpen() * 100;
        }

        // Pattern flags
        boolean strongOpening = c1BodyPct > 1.0 && c1WickRatio > 0.6;
        boolean breakout = Math.abs(gapPercent) > 0.5; // Gap > 0.5%
        boolean highVolBreakout = volumeRatio > 2.0 && breakout;

        return BacktestWinnerAnalysis.builder()
                .symbol(symbol)
                .tradeDate(tradeDate)
                .strategyName(strategyName)
                .outcome(trade.getOutcome())
                .direction(trade.getDirection())
                .entryPrice(trade.getEntryPrice())
                .exitPrice(trade.getExitPrice())
                .pnlPercent(trade.getPnlPercent())
                .actualRR(trade.getActualRR())
                .riskPoints(trade.getRiskPoints())
                .c1WickRatio(c1WickRatio)
                .c1BodyPct(c1BodyPct)
                .c1RangePct(c1RangePct)
                .c1Volume(trade.getC1Volume())
                .prevRsi(prevRsi > 0 ? prevRsi : null)
                .atrRatio(atrRatio > 0 ? atrRatio : null)
                .above20Dma(above20Dma)
                .prevDayChangePct(prevDayChg)
                .gapPercent(gapPercent)
                .volumeRatio(volumeRatio > 0 ? volumeRatio : null)
                .volumeFlag(trade.getVolumeFlag())
                .strongOpening(strongOpening)
                .breakout(breakout)
                .highVolBreakout(highVolBreakout)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // =========================================================================
    // Technical indicator calculations
    // =========================================================================

    private Candle findC1Candle(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return null;
        // C1 is typically the first candle (9:15-9:30)
        return candles.stream()
                .filter(c -> c.getTimestamp().toLocalTime().getHour() == 9)
                .filter(c -> c.getTimestamp().toLocalTime().getMinute() >= 15)
                .filter(c -> c.getTimestamp().toLocalTime().getMinute() < 30)
                .findFirst()
                .orElse(null);
    }

    private double calculateRsi(List<Candle> daily) {
        if (daily.size() < RSI_PERIOD + 1) return 0;

        List<Candle> recent = daily.subList(daily.size() - RSI_PERIOD - 1, daily.size());
        double avgGain = 0, avgLoss = 0;

        for (int i = 1; i < recent.size(); i++) {
            double change = recent.get(i).getClose() - recent.get(i - 1).getClose();
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= RSI_PERIOD;
        avgLoss /= RSI_PERIOD;

        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private double calculateAtrRatio(List<Candle> daily, BacktestTrade trade) {
        if (daily.size() < 15) return 0;

        List<Candle> recent = daily.subList(daily.size() - 15, daily.size());
        double avgAtr = 0;
        for (int i = 1; i < recent.size(); i++) {
            double tr = Math.max(recent.get(i).getHigh() - recent.get(i).getLow(),
                    Math.max(Math.abs(recent.get(i).getHigh() - recent.get(i-1).getClose()),
                            Math.abs(recent.get(i).getLow() - recent.get(i-1).getClose())));
            avgAtr += tr;
        }
        avgAtr /= RSI_PERIOD;

        double todayRange = trade.getC1High() - trade.getC1Low();
        return avgAtr > 0 ? todayRange / avgAtr : 0;
    }

    private boolean isAbove20Dma(List<Candle> daily, double ltp) {
        if (daily.size() < 20) return false;
        List<Candle> last20 = daily.subList(daily.size() - 20, daily.size());
        double dma20 = last20.stream().mapToDouble(Candle::getClose).average().orElse(0);
        return ltp > dma20;
    }

    private double calcPrevDayChange(List<Candle> daily) {
        if (daily.size() < 2) return 0;
        Candle prev = daily.get(daily.size() - 1);
        Candle prevPrev = daily.get(daily.size() - 2);
        return prevPrev.getClose() > 0
                ? (prev.getClose() - prevPrev.getClose()) / prevPrev.getClose() * 100
                : 0;
    }

    private double calculateVolumeRatio(List<Candle> daily, Long c1Volume) {
        if (daily.size() < 5 || c1Volume == null) return 0;
        
        List<Candle> last5 = daily.subList(daily.size() - 5, daily.size());
        double avgVol = last5.stream().mapToLong(Candle::getVolume).average().orElse(0);
        
        return avgVol > 0 ? c1Volume / avgVol : 0;
    }

    private List<Candle> fetchDailyCandles(String instrumentKey, LocalDate from, LocalDate to) {
        List<Candle> daily = new ArrayList<>();

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == java.time.DayOfWeek.SATURDAY ||
                    day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                continue;
            }

            try {
                List<Candle> intraday = candleService.fetchDayCandles(instrumentKey, day);
                if (intraday == null || intraday.isEmpty()) {
                    continue;
                }
                daily.add(toDailyCandle(intraday));
            } catch (Exception e) {
                log.debug("Skipping daily candle for {} on {}: {}", instrumentKey, day, e.getMessage());
            }
        }

        daily.sort(Comparator.comparing(Candle::getTimestamp));
        return daily;
    }

    private Candle toDailyCandle(List<Candle> intraday) {
        intraday.sort(Comparator.comparing(Candle::getTimestamp));

        Candle first = intraday.get(0);
        Candle last = intraday.get(intraday.size() - 1);
        double high = intraday.stream().mapToDouble(Candle::getHigh).max().orElse(first.getHigh());
        double low = intraday.stream().mapToDouble(Candle::getLow).min().orElse(first.getLow());
        long volume = intraday.stream().mapToLong(Candle::getVolume).sum();

        return Candle.builder()
                .timestamp(first.getTimestamp())
                .open(first.getOpen())
                .high(high)
                .low(low)
                .close(last.getClose())
                .volume(volume)
                .build();
    }

    // =========================================================================
    // Telegram reporting
    // =========================================================================

    private void sendTelegramReport(String strategyName, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏆 *Winner Analysis — ").append(strategyName).append("*\n");
        sb.append("📅 ").append(from).append(" to ").append(to).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        List<BacktestWinnerAnalysis> winners = analysisRepository
                .findByStrategyNameAndTradeDateBetweenOrderByTradeDateDesc(strategyName, from, to);

        if (winners.isEmpty()) {
            sb.append("No winners found in this period.");
            telegramService.sendMessage(sb.toString());
            return;
        }

        // Summary statistics
        long totalCount = winners.size();
        double avgPnl = winners.stream().mapToDouble(w -> w.getPnlPercent() != null ? w.getPnlPercent() : 0).average().orElse(0);
        long strongOpeningCount = winners.stream().filter(BacktestWinnerAnalysis::getStrongOpening).count();
        long breakoutCount = winners.stream().filter(BacktestWinnerAnalysis::getBreakout).count();
        long highVolBreakoutCount = winners.stream().filter(BacktestWinnerAnalysis::getHighVolBreakout).count();

        sb.append("📊 *Summary*\n");
        sb.append("Total Winners: ").append(totalCount).append("\n");
        sb.append("Avg P&L: ").append(String.format("%.2f%%", avgPnl)).append("\n");
        sb.append("Strong Opening: ").append(strongOpeningCount).append(" (").append(String.format("%.0f%%", strongOpeningCount * 100.0 / totalCount)).append(")\n");
        sb.append("Breakouts: ").append(breakoutCount).append(" (").append(String.format("%.0f%%", breakoutCount * 100.0 / totalCount)).append(")\n");
        sb.append("High-Vol Breakouts: ").append(highVolBreakoutCount).append(" (").append(String.format("%.0f%%", highVolBreakoutCount * 100.0 / totalCount)).append(")\n\n");

        // Pattern averages
        LocalDate since = to.minusDays(PATTERN_DAYS);
        long recentCount = analysisRepository.countByDateRange(since, to);
        if (recentCount >= 5) {
            Double avgRsi = analysisRepository.avgPrevRsi(since, to);
            Double avgAtr = analysisRepository.avgAtrRatio(since, to);
            Double avgVol = analysisRepository.avgVolumeRatio(since, to);
            Double avgGap = analysisRepository.avgGapPercent(since, to);

            sb.append("📈 *").append(PATTERN_DAYS).append("-day Pattern Averages*\n");
            if (avgRsi != null) sb.append("RSI: ").append(String.format("%.0f", avgRsi)).append("\n");
            if (avgAtr != null) sb.append("ATR Ratio: ").append(String.format("%.2fx", avgAtr)).append("\n");
            if (avgVol != null) sb.append("Volume Ratio: ").append(String.format("%.1fx", avgVol)).append("\n");
            if (avgGap != null) sb.append("Gap: ").append(String.format("%+.2f%%", avgGap)).append("\n");
            sb.append("\n");
        }

        // Top winners by P&L
        sb.append("🥇 *Top 5 Winners by P&L*\n");
        sb.append(String.format("%-12s %6s %5s %5s %5s %5s\n", "Symbol", "P&L%", "RSI", "Vol×", "Gap%", "ATR×"));
        sb.append("─────────────────────────────────────────\n");

        winners.stream()
                .sorted((a, b) -> Double.compare(
                        b.getPnlPercent() != null ? b.getPnlPercent() : 0,
                        a.getPnlPercent() != null ? a.getPnlPercent() : 0))
                .limit(5)
                .forEach(w -> {
                    sb.append(String.format("`%-11s` %+5.1f%% %4.0f %4.1fx %4.1f%% %4.1fx\n",
                            w.getSymbol(),
                            w.getPnlPercent() != null ? w.getPnlPercent() : 0,
                            w.getPrevRsi() != null ? w.getPrevRsi() : 0,
                            w.getVolumeRatio() != null ? w.getVolumeRatio() : 0,
                            w.getGapPercent(),
                            w.getAtrRatio() != null ? w.getAtrRatio() : 0));
                });

        telegramService.sendMessage(sb.toString());
        log.info("Winner analysis Telegram report sent for {}", strategyName);
    }
}
