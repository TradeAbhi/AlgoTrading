package com.trading.algo.service;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.trading.algo.dtos.Candle;
import com.trading.algo.dtos.WatchlistItem;
import com.trading.algo.entity.MoverCharacteristics;
import com.trading.algo.momentum.WatchlistService;
import com.trading.algo.repo.MoverCharacteristicsRepository;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Analyses characteristics of daily movers across 4 categories:
 *   TOP_GAINER / TOP_LOSER / VOLUME_SHOCKER / ACTIVE_BY_VALUE
 *
 * At 3:35 PM:
 *   1. Gets today's top movers from WatchlistService
 *   2. Fetches today's 9:15 candle (C1) for each mover
 *   3. Fetches 20 days of daily candles to calculate RSI, ATR, 20DMA
 *   4. Stores all characteristics in mover_characteristics table
 *   5. Sends Telegram report with today's characteristics + running pattern avg
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoverAnalysisService {

    private static final LocalTime C1_TIME    = LocalTime.of(9, 15);
    private static final int       HISTORY_DAYS = 35;
    private static final int       RSI_PERIOD   = 14;
    private static final int       PATTERN_DAYS = 10; // look back N days for pattern avg

    private final WatchlistService              watchlistService;
    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final MoverCharacteristicsRepository repo;
    private final TelegramService               telegramService;

    // =========================================================================
    // Main entry — called at 3:35 PM or manually
    // =========================================================================

    public void analyseToday() {
        analyse(LocalDate.now());
    }

    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledAnalyseToday() {
        analyseToday();
    }

    @Transactional
    public void analyse(LocalDate date) {
        log.info("MoverAnalysis START — date={}", date);

        // Get today's watchlist (last built snapshot)
        var watchlist = watchlistService.getLiveWatchlist();

        List<MoverCharacteristics> saved = new ArrayList<>();

        saved.addAll(processCategory(watchlist.getTopGainers(),     "TOP_GAINER",      date));
        saved.addAll(processCategory(watchlist.getTopLosers(),       "TOP_LOSER",       date));
        saved.addAll(processCategory(watchlist.getVolumeShockers(),  "VOLUME_SHOCKER",  date));
        saved.addAll(processCategory(watchlist.getActiveByValue(),   "ACTIVE_BY_VALUE", date));

        log.info("MoverAnalysis COMPLETE — {} records saved", saved.size());

        // Send Telegram report
        sendTelegramReport(date);
    }

    // =========================================================================
    // Process one category
    // =========================================================================

    private List<MoverCharacteristics> processCategory(
            List<WatchlistItem> items, String category, LocalDate date) {

        List<MoverCharacteristics> results = new ArrayList<>();
        if (items == null || items.isEmpty()) return results;

        for (WatchlistItem item : items) {
            try {
                // Skip if already processed today
                if (repo.existsBySymbolAndTradeDateAndCategory(item.getSymbol(), date, category)) {
                    continue;
                }

                Optional<String> keyOpt = instrumentMaster.getInstrumentKey(item.getSymbol());
                if (keyOpt.isEmpty()) {
                    log.debug("No instrument key for {}", item.getSymbol());
                    continue;
                }
                String key = keyOpt.get();

                // Fetch today's intraday candles for C1 characteristics
                List<Candle> todayCandles = candleService.fetchDayCandles(key, date);
                Candle c1 = todayCandles.stream()
                        .filter(c -> !c.getTimestamp().toLocalTime().isBefore(C1_TIME))
                        .findFirst().orElse(null);

                // Fetch enough calendar days to cover at least 20 trading candles for RSI, ATR, 20DMA.
                LocalDate histFrom = date.minusDays(HISTORY_DAYS);
                List<Candle> daily = fetchDailyCandles(key, histFrom, date.minusDays(1));

                // Calculate indicators
                double prevRsi      = daily.size() >= RSI_PERIOD + 1 ? calculateRsi(daily) : 0;
                double atrRatio     = daily.size() >= 15             ? calculateAtrRatio(daily, item) : 0;
                boolean above20Dma  = daily.size() >= 20         ? isAbove20Dma(daily, item.getLtp()) : false;
                double prevDayChg   = daily.isEmpty()            ? 0 : calcPrevDayChange(daily);

                // Gap %
                double gapPct = item.getClose() > 0
                        ? (item.getOpen() - item.getClose()) / item.getClose() * 100
                        : 0;

                // C1 characteristics
                double c1WickRatio = 0, c1BodyPct = 0, c1RangePct = 0;
                long   c1Volume    = 0;
                if (c1 != null) {
                    c1WickRatio = c1.wickRatio();
                    c1BodyPct   = c1.getOpen() > 0 ? c1.body()  / c1.getOpen() * 100 : 0;
                    c1RangePct  = c1.getOpen() > 0 ? c1.range() / c1.getOpen() * 100 : 0;
                    c1Volume    = c1.getVolume();
                }

                MoverCharacteristics mc = MoverCharacteristics.builder()
                        .symbol(item.getSymbol())
                        .tradeDate(date)
                        .category(category)
                        .changePercent(item.getChangePercent())
                        .openPrice(item.getOpen())
                        .highPrice(item.getHigh())
                        .lowPrice(item.getLow())
                        .closePrice(item.getLtp())
                        .gapPercent(gapPct)
                        .volumeRatio(item.getVolumeRatio())
                        .tradedValueCr(item.getTradedValue())
                        .c1WickRatio(c1WickRatio)
                        .c1BodyPct(c1BodyPct)
                        .c1RangePct(c1RangePct)
                        .c1Volume(c1Volume)
                        .prevRsi(prevRsi > 0 ? prevRsi : null)
                        .atrRatio(atrRatio > 0 ? atrRatio : null)
                        .above20Dma(above20Dma)
                        .prevDayChangePct(prevDayChg)
                        .createdAt(LocalDateTime.now())
                        .build();

                repo.save(mc);
                results.add(mc);

                log.info("Saved {} {} - chg={}%, vol={}x, rsi={}, atr={}x, c1Wick={}",
                        category, item.getSymbol(), item.getChangePercent(),
                        item.getVolumeRatio(), prevRsi, atrRatio, c1WickRatio);

                Thread.sleep(150); // rate limit

            } catch (Exception e) {
                log.error("Error analysing {} {}: {}", category, item.getSymbol(), e.getMessage());
            }
        }
        return results;
    }

    // =========================================================================
    // Telegram report
    // =========================================================================

    private void sendTelegramReport(LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔬 *Mover Analysis — ").append(date).append("*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        for (String[] catInfo : new String[][]{
                {"TOP_GAINER",      "📈 Top Gainers"},
                {"TOP_LOSER",       "📉 Top Losers"},
                {"VOLUME_SHOCKER",  "🔥 Volume Shockers"},
                {"ACTIVE_BY_VALUE", "💰 Active by Value"}
        }) {
            String category = catInfo[0];
            String label    = catInfo[1];

            List<MoverCharacteristics> movers = new ArrayList<>(
                    repo.findByTradeDateAndCategoryOrderByChangePercentDesc(date, category));

            if (movers.isEmpty()) continue;
            if ("TOP_LOSER".equals(category)) {
                movers.sort(Comparator.comparingDouble(MoverCharacteristics::getChangePercent));
            }

            sb.append(label).append("\n");
            sb.append(String.format("%-12s %6s %6s %5s %5s %5s %5s\n",
                    "Symbol", "Chg%", "Vol×", "C1Wk", "RSI", "ATR×", "Gap%"));
            sb.append("─────────────────────────────────────────\n");

            for (MoverCharacteristics m : movers) {
                sb.append(String.format("`%-11s` %+5.1f%% %5.0fx %4.2f %4.0f %4.1fx %+4.1f%%\n",
                        m.getSymbol(),
                        m.getChangePercent(),
                        m.getVolumeRatio(),
                        m.getC1WickRatio(),
                        m.getPrevRsi() != null ? m.getPrevRsi() : 0,
                        m.getAtrRatio() != null ? m.getAtrRatio() : 0,
                        m.getGapPercent()));
            }

            // Pattern averages from last N days
            LocalDate since = date.minusDays(PATTERN_DAYS);
            long  count     = repo.countByCategorySince(category, since);
            if (count >= 5) {
                Double avgVol  = repo.avgVolumeRatio(category, since);
                Double avgRsi  = repo.avgPrevRsi(category, since);
                Double avgAtr  = repo.avgAtrRatio(category, since);
                Double avgWick = repo.avgC1WickRatio(category, since);
                Double avgGap  = repo.avgGapPercent(category, since);

                sb.append(String.format(
                        "📊 _%d-day avg: Vol×%.0f | RSI%.0f | ATR×%.1f | Wick%.2f | Gap%+.1f%%_\n",
                        PATTERN_DAYS,
                        avgVol  != null ? avgVol  : 0,
                        avgRsi  != null ? avgRsi  : 0,
                        avgAtr  != null ? avgAtr  : 0,
                        avgWick != null ? avgWick : 0,
                        avgGap  != null ? avgGap  : 0));
            }
            sb.append("\n");
        }

        telegramService.sendMessage(sb.toString());
        log.info("Mover analysis Telegram report sent for {}", date);
    }

    // =========================================================================
    // Technical indicators
    // =========================================================================

    /** RSI(14) using Wilder's smoothing */
    private double calculateRsi(List<Candle> daily) {
        if (daily.size() < RSI_PERIOD + 1) return 0;

        List<Candle> recent = daily.subList(daily.size() - RSI_PERIOD - 1, daily.size());
        double avgGain = 0, avgLoss = 0;

        for (int i = 1; i < recent.size(); i++) {
            double change = recent.get(i).getClose() - recent.get(i - 1).getClose();
            if (change > 0) avgGain += change;
            else            avgLoss += Math.abs(change);
        }
        avgGain /= RSI_PERIOD;
        avgLoss /= RSI_PERIOD;

        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /** ATR ratio = today's range / 14-day average true range */
    private double calculateAtrRatio(List<Candle> daily, WatchlistItem today) {
        if (daily.size() < 15) return 0;

        List<Candle> recent = daily.subList(daily.size() - 15, daily.size());
        double avgAtr = 0;
        for (int i = 1; i < recent.size(); i++) {
            double tr = Math.max(recent.get(i).getHigh() - recent.get(i).getLow(),
                    Math.max(Math.abs(recent.get(i).getHigh() - recent.get(i-1).getClose()),
                            Math.abs(recent.get(i).getLow()  - recent.get(i-1).getClose())));
            avgAtr += tr;
        }
        avgAtr /= RSI_PERIOD;

        double todayRange = today.getHigh() - today.getLow();
        return avgAtr > 0 ? todayRange / avgAtr : 0;
    }

    /** Is today's LTP above 20-day moving average? */
    private boolean isAbove20Dma(List<Candle> daily, double ltp) {
        if (daily.size() < 20) return false;
        List<Candle> last20 = daily.subList(daily.size() - 20, daily.size());
        double dma20 = last20.stream().mapToDouble(Candle::getClose).average().orElse(0);
        return ltp > dma20;
    }

    /** Previous day's % change */
    private double calcPrevDayChange(List<Candle> daily) {
        if (daily.size() < 2) return 0;
        Candle prev     = daily.get(daily.size() - 1);
        Candle prevPrev = daily.get(daily.size() - 2);
        return prevPrev.getClose() > 0
                ? (prev.getClose() - prevPrev.getClose()) / prevPrev.getClose() * 100
                : 0;
    }

    /** Fetch daily candles from Upstox */
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

}
