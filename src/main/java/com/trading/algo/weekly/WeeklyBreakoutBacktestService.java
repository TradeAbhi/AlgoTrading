package com.trading.algo.weekly;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.trading.algo.config.OrbConfig;
import com.trading.algo.telegram.TelegramService;

/**
 * Weekly Breakout Backtest Service
 *
 * Replays the weekly breakout strategy on historical data.
 * For each week in the date range, seeds the previous week's high/low,
 * then evaluates each daily candle Mon–Fri for a breakout signal.
 *
 * Mirrors WeeklyBreakoutScannerService logic exactly:
 *   - Weekly range filter: 1.5% ≤ range ≤ 8%
 *   - Volume filter: daily volume ≥ 1.5× average daily volume (weeklyVol / 5)
 *   - weeklyHigh only moves UP on pierce-no-close
 *   - weeklyLow  only moves DOWN on pierce-no-close
 *   - SL = prev daily low (BUY) or prev daily high (SELL)
 *
 * REST: GET /weekly/backtest?from=2025-01-01&to=2025-06-30&stopLossPct=2.0
 */
@Service
public class WeeklyBreakoutBacktestService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyBreakoutBacktestService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final double MIN_WEEKLY_RANGE_PCT  = 1.5;
    private static final double MAX_WEEKLY_RANGE_PCT  = 8.0;
    private static final double MIN_VOLUME_MULTIPLIER = 1.5;

    private final OrbConfig orbConfig;
    private final RestTemplate restTemplate;
    private final TelegramService telegramService;

    public WeeklyBreakoutBacktestService(OrbConfig orbConfig,
                                          RestTemplate restTemplate,
                                          TelegramService telegramService) {
        this.orbConfig       = orbConfig;
        this.restTemplate    = restTemplate;
        this.telegramService = telegramService;
    }

    // ── Async entry point ─────────────────────────────────────────────────────
    @Async("backtestExecutor")
    public void runBacktestAsync(LocalDate fromDate, LocalDate toDate,
                                  double stopLossPct, String requestId) {
        try {
            run(fromDate, toDate, stopLossPct, requestId);
        } catch (Exception e) {
            log.error("[WEEKLY-BT][{}] Backtest failed: {}", requestId, e.getMessage(), e);
            telegramService.sendMessage(String.format(
                "❌ *Weekly Backtest Failed*%n🆔 `%s`%n⚠️ %s", requestId, e.getMessage()));
        }
    }

    public BacktestSummary run(LocalDate fromDate, LocalDate toDate, double stopLossPct) {
        return run(fromDate, toDate, stopLossPct, "manual");
    }

    // ── Core backtest ─────────────────────────────────────────────────────────
    public BacktestSummary run(LocalDate fromDate, LocalDate toDate,
                                double stopLossPct, String requestId) {
        log.info("[WEEKLY-BT][{}] Starting: {} to {} | SL: {}%",
            requestId, fromDate, toDate, stopLossPct);

        List<TradeResult> allTrades = new ArrayList<>();
        Map<String, String> keyToSymbol = orbConfig.getKeyToSymbolMap();
        List<LocalDate> mondays = getMondaysInRange(fromDate, toDate);

        log.info("[WEEKLY-BT][{}] {} symbols × {} weeks",
            requestId, keyToSymbol.size(), mondays.size());

        int processed = 0;
        for (Map.Entry<String, String> entry : keyToSymbol.entrySet()) {
            String instrumentKey = entry.getKey();
            String symbol        = entry.getValue();
            processed++;

            if (processed % 50 == 0) {
                log.info("[WEEKLY-BT][{}] Progress: {}/{}", requestId, processed, keyToSymbol.size());
            }

            for (LocalDate monday : mondays) {
                try {
                    allTrades.addAll(evaluateWeek(symbol, instrumentKey, monday, stopLossPct));
                } catch (Exception e) {
                    log.debug("[WEEKLY-BT][{}] Skipping {} week of {}: {}",
                        requestId, symbol, monday, e.getMessage());
                }
            }
        }

        BacktestSummary summary = buildSummary(allTrades, fromDate, toDate, stopLossPct);
        sendResultsToTelegram(summary, allTrades, fromDate, toDate, stopLossPct, requestId);
        return summary;
    }

    // ── Evaluate one symbol for one week ─────────────────────────────────────
    private List<TradeResult> evaluateWeek(String symbol, String instrumentKey,
                                            LocalDate monday, double stopLossPct) {
        List<TradeResult> trades = new ArrayList<>();

        // Fetch the week before monday's weekly candle
        LocalDate prevWeekEnd   = monday.minusDays(1);
        LocalDate prevWeekStart = monday.minusDays(7);
        List<List<Object>> weeklyCandles = fetchHistoricalWeekly(
            instrumentKey, prevWeekStart, prevWeekEnd);

        if (weeklyCandles == null || weeklyCandles.isEmpty()) return trades;

        List<Object> prevWeek = weeklyCandles.get(0);
        double wHigh   = toDouble(prevWeek.get(2));
        double wLow    = toDouble(prevWeek.get(3));
        long   wVolume = toLong(prevWeek.get(5));

        // Filter 1: weekly range width
        double rangeWidth = ((wHigh - wLow) / wLow) * 100.0;
        if (rangeWidth < MIN_WEEKLY_RANGE_PCT || rangeWidth > MAX_WEEKLY_RANGE_PCT) return trades;

        long avgDailyVol   = wVolume / 5;
        double weeklyHigh  = wHigh;
        double weeklyLow   = wLow;
        boolean buyDone    = false;
        boolean sellDone   = false;
        double prevDHigh   = wHigh;
        double prevDLow    = wLow;

        // Fetch daily candles for the current week (Mon–Fri)
        LocalDate friday = monday.plusDays(4);
        List<List<Object>> dailyCandles = fetchHistoricalDaily(instrumentKey, monday, friday);
        if (dailyCandles == null || dailyCandles.isEmpty()) return trades;

        // Daily candles returned newest first — reverse to chronological
        Collections.reverse(dailyCandles);

        for (List<Object> day : dailyCandles) {
            double dHigh   = toDouble(day.get(2));
            double dLow    = toDouble(day.get(3));
            double dClose  = toDouble(day.get(4));
            long   dVolume = toLong(day.get(5));
            String date    = day.get(0).toString().substring(0, 10);

            // ── BUY check ───────────────────────────────────────────────────
            if (!buyDone) {
                if (dClose > weeklyHigh) {
                    if (dVolume >= (long)(avgDailyVol * MIN_VOLUME_MULTIPLIER)) {
                        buyDone = true;
                        double entry       = dClose;
                        double invalidation = prevDLow;
                        double stopPrice   = entry * (1 - stopLossPct / 100.0);
                        double pnlPct      = simulateExit(dailyCandles,
                            dailyCandles.indexOf(day) + 1, stopPrice, "BUY", entry);

                        trades.add(new TradeResult(symbol, LocalDate.parse(date),
                            "BUY", entry, pnlPct, weeklyHigh, invalidation));
                    }
                } else if (dHigh > weeklyHigh) {
                    weeklyHigh = dHigh; // only moves up
                }
            }

            // ── SELL check ──────────────────────────────────────────────────
            if (!sellDone) {
                if (dClose < weeklyLow) {
                    if (dVolume >= (long)(avgDailyVol * MIN_VOLUME_MULTIPLIER)) {
                        sellDone = true;
                        double entry        = dClose;
                        double invalidation = prevDHigh;
                        double stopPrice    = entry * (1 + stopLossPct / 100.0);
                        double pnlPct       = simulateExit(dailyCandles,
                            dailyCandles.indexOf(day) + 1, stopPrice, "SELL", entry);

                        trades.add(new TradeResult(symbol, LocalDate.parse(date),
                            "SELL", entry, pnlPct, weeklyLow, invalidation));
                    }
                } else if (dLow < weeklyLow) {
                    weeklyLow = dLow; // only moves down
                }
            }

            prevDHigh = dHigh;
            prevDLow  = dLow;

            if (buyDone && sellDone) break;
        }

        return trades;
    }

    // ── Simulate exit on subsequent daily candles ─────────────────────────────
    private double simulateExit(List<List<Object>> candles, int fromIdx,
                                 double stopPrice, String side, double entry) {
        for (int i = fromIdx; i < candles.size(); i++) {
            List<Object> c    = candles.get(i);
            double dLow       = toDouble(c.get(3));
            double dHigh      = toDouble(c.get(2));
            double dClose     = toDouble(c.get(4));

            if ("BUY".equals(side)  && dLow  <= stopPrice) {
                return ((stopPrice - entry) / entry) * 100.0;
            }
            if ("SELL".equals(side) && dHigh >= stopPrice) {
                return ((entry - stopPrice) / entry) * 100.0;
            }

            // Exit at week end (Friday = last candle in our fetched range)
            if (i == candles.size() - 1) {
                return "BUY".equals(side)
                    ? ((dClose - entry) / entry) * 100.0
                    : ((entry - dClose) / entry) * 100.0;
            }
        }
        return 0;
    }

    // ── Upstox historical fetchers ────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private List<List<Object>> fetchHistoricalWeekly(String instrumentKey,
                                                      LocalDate from, LocalDate to) {
        try {
            String encoded = instrumentKey.replace("|", "%7C");
            java.net.URI uri = java.net.URI.create(String.format(
                "https://api.upstox.com/v3/historical-candle/%s/weeks/1/%s/%s",
                encoded, to.format(DATE_FMT), from.format(DATE_FMT)));

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return null;
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            return data == null ? null : (List<List<Object>>) data.get("candles");
        } catch (Exception e) {
            log.debug("[WEEKLY-BT] fetchHistoricalWeekly failed: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> fetchHistoricalDaily(String instrumentKey,
                                                     LocalDate from, LocalDate to) {
        try {
            String encoded = instrumentKey.replace("|", "%7C");
            java.net.URI uri = java.net.URI.create(String.format(
                "https://api.upstox.com/v3/historical-candle/%s/days/1/%s/%s",
                encoded, to.format(DATE_FMT), from.format(DATE_FMT)));

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return null;
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            return data == null ? null : (List<List<Object>>) data.get("candles");
        } catch (Exception e) {
            log.debug("[WEEKLY-BT] fetchHistoricalDaily failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Summary + Telegram ────────────────────────────────────────────────────
    private BacktestSummary buildSummary(List<TradeResult> trades,
                                          LocalDate from, LocalDate to, double slPct) {
        if (trades.isEmpty()) return new BacktestSummary(from, to, slPct, 0, 0, 0, 0, 0, 0, 0, 0);

        long wins    = trades.stream().filter(t -> t.pnlPct > 0).count();
        long losses  = trades.stream().filter(t -> t.pnlPct <= 0).count();
        double total = trades.stream().mapToDouble(t -> t.pnlPct).sum();
        double avg   = total / trades.size();
        double best  = trades.stream().mapToDouble(t -> t.pnlPct).max().orElse(0);
        double worst = trades.stream().mapToDouble(t -> t.pnlPct).min().orElse(0);
        double wr    = (wins * 100.0) / trades.size();

        double maxDd = 0, running = 0, peak = 0;
        for (TradeResult t : trades) {
            running += t.pnlPct;
            if (running > peak) peak = running;
            double dd = peak - running;
            if (dd > maxDd) maxDd = dd;
        }

        return new BacktestSummary(from, to, slPct,
            trades.size(), (int) wins, (int) losses, wr, avg, best, worst, maxDd);
    }

    private void sendResultsToTelegram(BacktestSummary s, List<TradeResult> trades,
                                        LocalDate from, LocalDate to,
                                        double slPct, String requestId) {
        telegramService.sendMessage(String.format(
            "📊 *Weekly Breakout Backtest*%n" +
            "🆔 `%s`%n" +
            "📅 %s → %s | SL: %.1f%%%n" +
            "────────────────%n" +
            "📋 Total Trades:  %d%n" +
            "✅ Wins:          %d%n" +
            "❌ Losses:        %d%n" +
            "🎯 Win Rate:      %.1f%%%n" +
            "💰 Avg P\\&L:      %.2f%%%n" +
            "🚀 Best Trade:    %.2f%%%n" +
            "💥 Worst Trade:   %.2f%%%n" +
            "📉 Max Drawdown:  %.2f%%",
            requestId, from, to, slPct,
            s.totalTrades, s.wins, s.losses, s.winRate,
            s.avgPnl, s.bestTrade, s.worstTrade, s.maxDrawdown));

        byte[] csv      = buildCsv(trades);
        String fileName = String.format("weekly_backtest_%s_to_%s_%s.csv",
            from, to, requestId.substring(0, 8));
        telegramService.sendDocument(csv, fileName,
            String.format("Weekly Breakout %s → %s | %d trades", from, to, trades.size()));
    }

    private byte[] buildCsv(List<TradeResult> trades) {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol,Date,Side,Entry Price,P&L %,Weekly Level,Invalidation\n");
        for (TradeResult t : trades) {
            sb.append(String.format("%s,%s,%s,%.2f,%.2f,%.2f,%.2f%n",
                t.symbol, t.date, t.side,
                t.entryPrice, t.pnlPct, t.level, t.invalidation));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────
    private List<LocalDate> getMondaysInRange(LocalDate from, LocalDate to) {
        List<LocalDate> mondays = new ArrayList<>();
        LocalDate d = from;
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek() == DayOfWeek.MONDAY) mondays.add(d);
            d = d.plusDays(1);
        }
        return mondays;
    }

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private long toLong(Object val) {
        return val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString());
    }

    // ── Inner models ──────────────────────────────────────────────────────────
    public static class TradeResult {
        public final String    symbol;
        public final LocalDate date;
        public final String    side;
        public final double    entryPrice;
        public final double    pnlPct;
        public final double    level;
        public final double    invalidation;

        TradeResult(String symbol, LocalDate date, String side,
                    double entryPrice, double pnlPct,
                    double level, double invalidation) {
            this.symbol       = symbol;
            this.date         = date;
            this.side         = side;
            this.entryPrice   = entryPrice;
            this.pnlPct       = pnlPct;
            this.level        = level;
            this.invalidation = invalidation;
        }
    }

    public static class BacktestSummary {
        public final LocalDate from, to;
        public final double stopLossPct;
        public final int    totalTrades, wins, losses;
        public final double winRate, avgPnl, bestTrade, worstTrade, maxDrawdown;

        BacktestSummary(LocalDate from, LocalDate to, double stopLossPct,
                        int totalTrades, int wins, int losses,
                        double winRate, double avgPnl,
                        double bestTrade, double worstTrade, double maxDrawdown) {
            this.from        = from;
            this.to          = to;
            this.stopLossPct = stopLossPct;
            this.totalTrades = totalTrades;
            this.wins        = wins;
            this.losses      = losses;
            this.winRate     = winRate;
            this.avgPnl      = avgPnl;
            this.bestTrade   = bestTrade;
            this.worstTrade  = worstTrade;
            this.maxDrawdown = maxDrawdown;
        }
    }
}