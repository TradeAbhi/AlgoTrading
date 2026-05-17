package com.trading.algo.orb;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * ORB Backtest Service
 *
 * Replays the ORB strategy on historical 15-minute candle data from Upstox
 * for every symbol in Nifty 500 across a given date range.
 *
 * Matches live OrbScannerServiceFinal exactly:
 *   - Opening range = 9:15 candle high/low (seeded from first 15-min candle of the day)
 *   - rollingHigh/rollingLow advance each candle if no confirmation yet
 *   - BUY  triggered when 15-min candle closes above rollingHigh
 *   - SELL triggered when 15-min candle closes below rollingLow
 *   - Scans begin from 9:46 candle (9:30–9:45 close) onwards
 *   - Exit: stop-loss hit OR 3:15 PM candle close (EOD)
 *
 * Output → Telegram:
 *   - Summary message (win rate, avg P&L, drawdown, best/worst)
 *   - Full trade CSV as document attachment
 *
 * REST: GET /orb/backtest?from=2025-01-01&to=2025-03-31&stopLossPct=1.5
 */
@Service
public class OrbBacktestService {

    private static final Logger log = LoggerFactory.getLogger(OrbBacktestService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime SCAN_START   = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 15);

    // ── Filters (must match OrbScannerServiceFinal) ───────────────────────────
    private static final double MIN_RANGE_PCT         = 0.5;
    private static final double MIN_VOLUME_MULTIPLIER = 1.5;

    private final OrbConfig orbConfig;
    private final RestTemplate restTemplate;
    private final TelegramService telegramService;

    public OrbBacktestService(OrbConfig orbConfig,
                               RestTemplate restTemplate,
                               TelegramService telegramService) {
        this.orbConfig       = orbConfig;
        this.restTemplate    = restTemplate;
        this.telegramService = telegramService;
    }

    // ── Async entry point (called by OrbBacktestController) ─────────────────
    @Async("backtestExecutor")
    public void runBacktestAsync(LocalDate fromDate, LocalDate toDate,
                                  double stopLossPct, String requestId) {
        try {
            run(fromDate, toDate, stopLossPct, requestId);
        } catch (Exception e) {
            log.error("[ORB-BT][{}] Backtest failed: {}", requestId, e.getMessage(), e);
            telegramService.sendMessage(String.format(
                "❌ *ORB Backtest Failed*%n🆔 `%s`%n⚠️ %s", requestId, e.getMessage()));
        }
    }

    // ── Synchronous overload (manual / test calls) ───────────────────────────
    public BacktestSummary run(LocalDate fromDate, LocalDate toDate, double stopLossPct) {
        return run(fromDate, toDate, stopLossPct, "manual");
    }

    // ── Core backtest logic ──────────────────────────────────────────────────
    public BacktestSummary run(LocalDate fromDate, LocalDate toDate,
                                double stopLossPct, String requestId) {
        log.info("[ORB-BT][{}] Starting: {} to {} | SL: {}%",
            requestId, fromDate, toDate, stopLossPct);

        List<TradeResult> allTrades = new ArrayList<>();
        Map<String, String> keyToSymbol = orbConfig.getKeyToSymbolMap();
        List<LocalDate> tradingDays = getTradingDays(fromDate, toDate);

        log.info("[ORB-BT][{}] {} symbols × {} trading days",
            requestId, keyToSymbol.size(), tradingDays.size());

        int processed = 0;
        int total = keyToSymbol.size();

        for (Map.Entry<String, String> entry : keyToSymbol.entrySet()) {
            String instrumentKey = entry.getKey();
            String symbol        = entry.getValue();
            processed++;

            if (processed % 50 == 0) {
                log.info("[ORB-BT][{}] Progress: {}/{} symbols", requestId, processed, total);
            }

            for (LocalDate date : tradingDays) {
                try {
                    List<Candle> candles = fetchDayCandles(instrumentKey, symbol, date);
                    if (candles == null || candles.size() < 2) continue;

                    allTrades.addAll(evaluateDay(symbol, date, candles, stopLossPct));

                } catch (Exception e) {
                    log.debug("[ORB-BT][{}] Skipping {} on {}: {}", requestId, symbol, date, e.getMessage());
                }
            }
        }

        BacktestSummary summary = buildSummary(allTrades, fromDate, toDate, stopLossPct);
        sendResultsToTelegram(summary, allTrades, fromDate, toDate, stopLossPct, requestId);
        return summary;
    }

    // ── Evaluate one symbol for one day ─────────────────────────────────────
    private List<TradeResult> evaluateDay(String symbol, LocalDate date,
                                           List<Candle> candles, double stopLossPct) {
        List<TradeResult> trades = new ArrayList<>();

        // candles sorted oldest → newest; first candle must be the 9:15 opening candle
        Candle firstCandle = candles.get(0);
        if (!firstCandle.time.toLocalTime().equals(MARKET_OPEN)) return trades;

        // ── Filter 1: Minimum opening range width ────────────────────────
        double rangeWidth = ((firstCandle.high - firstCandle.low) / firstCandle.low) * 100.0;
        if (rangeWidth < MIN_RANGE_PCT) {
            return trades; // opening range too narrow — skip symbol for this day
        }

        double rollingHigh    = firstCandle.high;
        double rollingLow     = firstCandle.low;
        boolean buyTriggered  = false;
        boolean sellTriggered = false;
        long openingVolume    = firstCandle.volume;

        // prevCandle seeded from 9:15 candle — used if the very first scan triggers
        double prevCandleHigh = firstCandle.high;
        double prevCandleLow  = firstCandle.low;

        for (int i = 1; i < candles.size(); i++) {
            Candle c = candles.get(i);

            // Candle timestamp = candle open time. Skip candles opening before 9:30
            // (i.e. only the 9:15 candle which is already seeded as opening range).
            // First signal-eligible candle is 9:30 (the 9:30–9:45 period),
            // matching the live scanner's first scan at 9:46.
            if (c.time.toLocalTime().isBefore(SCAN_START)) {
                prevCandleHigh = c.high;
                prevCandleLow  = c.low;
                continue;
            }

            // ── BUY check ───────────────────────────────────────────────────
            // rollingLow stays frozen until BUY triggers.
            // On trigger: shift rollingLow to previous candle's low (invalidation).
            // On pierce-no-close: advance rollingHigh only.
            if (!buyTriggered) {
                if (c.close > rollingHigh) {
                    // ── Filter 2: Volume confirmation ─────────────────────────
                    if (c.volume >= (long)(openingVolume * MIN_VOLUME_MULTIPLIER)) {
                        buyTriggered = true;
                        double entryPrice   = c.close;
                        double invalidation = prevCandleLow;
                        double stopPrice    = entryPrice * (1 - stopLossPct / 100.0);
                        double exitPrice    = findExit(candles, i + 1, stopPrice, "BUY");
                        double pnlPct       = ((exitPrice - entryPrice) / entryPrice) * 100.0;

                        trades.add(new TradeResult(symbol, date, "BUY",
                            entryPrice, exitPrice, pnlPct, rollingHigh, invalidation, c.time.toLocalTime()));
                    }
                    // low volume breakout — skip, but don't advance rollingHigh

                } else if (c.high > rollingHigh) {
                    // Pierced rollingHigh but failed to close above →
                    // rollingHigh moves UP to this candle's high (only ever moves higher)
                    rollingHigh = c.high;
                }
                // c.high <= rollingHigh → didn't reach level, nothing changes
            }

            // ── SELL check ──────────────────────────────────────────────────
            // rollingLow can only move LOWER — never higher.
            // rollingHigh stays frozen at 9:15 high until SELL triggers.
            // On trigger: shift rollingHigh to previous candle's high (invalidation).
            if (!sellTriggered) {
                if (c.close < rollingLow) {
                    // ── Filter 2: Volume confirmation ─────────────────────────
                    if (c.volume >= (long)(openingVolume * MIN_VOLUME_MULTIPLIER)) {
                        sellTriggered = true;
                        double entryPrice   = c.close;
                        double invalidation = prevCandleHigh;
                        double stopPrice    = entryPrice * (1 + stopLossPct / 100.0);
                        double exitPrice    = findExit(candles, i + 1, stopPrice, "SELL");
                        double pnlPct       = ((entryPrice - exitPrice) / entryPrice) * 100.0;

                        trades.add(new TradeResult(symbol, date, "SELL",
                            entryPrice, exitPrice, pnlPct, rollingLow, invalidation, c.time.toLocalTime()));
                    }
                    // low volume breakdown — skip, but don't advance rollingLow

                } else if (c.low < rollingLow) {
                    // Pierced rollingLow but failed to close below →
                    // rollingLow moves DOWN to this candle's low (only ever moves lower)
                    rollingLow = c.low;
                }
                // c.low >= rollingLow → didn't reach level, nothing changes
            }

            // Track previous candle for next iteration
            prevCandleHigh = c.high;
            prevCandleLow  = c.low;

            if (buyTriggered && sellTriggered) break;
        }

        return trades;
    }

    // ── Find exit: stop-loss hit OR EOD ─────────────────────────────────────
    /**
     * Finds the exit price for a trade.
     * BUY  — exits if any candle's low touches stopPrice, else exits at EOD close.
     * SELL — exits if any candle's high touches stopPrice, else exits at EOD close.
     */
    private double findExit(List<Candle> candles, int fromIdx,
                             double stopPrice, String side) {
        for (int i = fromIdx; i < candles.size(); i++) {
            Candle c = candles.get(i);

            if ("BUY".equals(side)  && c.low  <= stopPrice) return stopPrice;
            if ("SELL".equals(side) && c.high >= stopPrice) return stopPrice;

            if (!c.time.toLocalTime().isBefore(MARKET_CLOSE)) return c.close;
        }
        return candles.isEmpty() ? 0 : candles.get(candles.size() - 1).close;
    }

    // ── Upstox historical 15-min candle fetch ────────────────────────────────
    @SuppressWarnings("unchecked")
    private List<Candle> fetchDayCandles(String instrumentKey, String symbol, LocalDate date) {
        String dateStr = date.format(DATE_FMT);
        // v3 format: /v3/historical-candle/{key}/minutes/15/{to_date}/{from_date}
        // '|' is illegal in a URI path — encode it manually before URI.create()
        String encodedKey = instrumentKey.replace("|", "%7C");
        java.net.URI uri = java.net.URI.create(String.format(
            "https://api.upstox.com/v3/historical-candle/%s/minutes/15/%s/%s",
            encodedKey, dateStr, dateStr
        ));
        Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
        if (response == null || !"success".equals(response.get("status"))) return null;

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return null;

        List<List<Object>> raw = (List<List<Object>>) data.get("candles");
        if (raw == null || raw.isEmpty()) return null;

        List<Candle> candles = new ArrayList<>();
        for (List<Object> c : raw) {
            String rawTs = c.get(0).toString().substring(0, 19);
            candles.add(new Candle(
                java.time.LocalDateTime.parse(rawTs, DT_FMT),
                toDouble(c.get(1)),
                toDouble(c.get(2)),
                toDouble(c.get(3)),
                toDouble(c.get(4)),
                toLong(c.get(5))
            ));
        }

        // Upstox returns newest first — reverse to chronological order
        Collections.reverse(candles);
        return candles;
    }

    // ── Build summary stats ──────────────────────────────────────────────────
    private BacktestSummary buildSummary(List<TradeResult> trades,
                                          LocalDate from, LocalDate to, double slPct) {
        if (trades.isEmpty()) {
            return new BacktestSummary(from, to, slPct, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        long wins    = trades.stream().filter(t -> t.pnlPct > 0).count();
        long losses  = trades.stream().filter(t -> t.pnlPct <= 0).count();
        double total = trades.stream().mapToDouble(t -> t.pnlPct).sum();
        double avg   = total / trades.size();
        double best  = trades.stream().mapToDouble(t -> t.pnlPct).max().orElse(0);
        double worst = trades.stream().mapToDouble(t -> t.pnlPct).min().orElse(0);
        double wr    = (wins * 100.0) / trades.size();

        double maxDrawdown = 0, running = 0, peak = 0;
        for (TradeResult t : trades) {
            running += t.pnlPct;
            if (running > peak) peak = running;
            double dd = peak - running;
            if (dd > maxDrawdown) maxDrawdown = dd;
        }

        log.info("[ORB-BT] {} trades | WR: {}% | Avg: {}% | Best: {}% | Worst: {}%",
            trades.size(),
            String.format("%.1f", wr),
            String.format("%.2f", avg),
            String.format("%.2f", best),
            String.format("%.2f", worst));

        return new BacktestSummary(from, to, slPct,
            trades.size(), (int) wins, (int) losses, wr, avg, best, worst, maxDrawdown);
    }

    // ── Send results to Telegram ─────────────────────────────────────────────
    private void sendResultsToTelegram(BacktestSummary s, List<TradeResult> trades,
                                        LocalDate from, LocalDate to,
                                        double slPct, String requestId) {
        telegramService.sendMessage(String.format(
            "📊 *ORB Backtest Results*%n" +
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
            requestId,
            from, to, slPct,
            s.totalTrades,
            s.wins,
            s.losses,
            s.winRate,
            s.avgPnl,
            s.bestTrade,
            s.worstTrade,
            s.maxDrawdown
        ));

        byte[] csv      = buildCsv(trades);
        String fileName = String.format("orb_backtest_%s_to_%s_%s.csv", from, to, requestId.substring(0, 8));
        telegramService.sendDocument(csv, fileName,
            String.format("ORB Backtest %s → %s | %d trades | ID: `%s`",
                from, to, trades.size(), requestId));
    }

    // ── Build CSV ────────────────────────────────────────────────────────────
    private byte[] buildCsv(List<TradeResult> trades) {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol,Date,Side,Entry Price,Exit Price,P&L %,Level,Invalidation,Trigger Time\n");
        for (TradeResult t : trades) {
            sb.append(String.format("%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%s%n",
                t.symbol, t.date, t.side,
                t.entryPrice, t.exitPrice, t.pnlPct,
                t.level, t.invalidation, t.triggerTime));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Trading days (Mon–Fri) ────────────────────────────────────────────────
    private List<LocalDate> getTradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = from;
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
            d = d.plusDays(1);
        }
        return days;
    }

    // ── Type helpers ─────────────────────────────────────────────────────────
    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private long toLong(Object val) {
        return val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString());
    }

    // ── Inner models ─────────────────────────────────────────────────────────

    private static class Candle {
        final java.time.LocalDateTime time;
        final double open, high, low, close;
        final long volume;

        Candle(java.time.LocalDateTime time, double open, double high,
               double low, double close, long volume) {
            this.time   = time;
            this.open   = open;
            this.high   = high;
            this.low    = low;
            this.close  = close;
            this.volume = volume;
        }
    }

    public static class TradeResult {
        public final String symbol;
        public final LocalDate date;
        public final String side;
        public final double entryPrice;
        public final double exitPrice;
        public final double pnlPct;
        public final double level;          // rollingHigh (BUY) or rollingLow (SELL) at trigger
        public final double invalidation;   // prevCandleLow (BUY) or prevCandleHigh (SELL)
        public final LocalTime triggerTime;

        TradeResult(String symbol, LocalDate date, String side,
                    double entryPrice, double exitPrice, double pnlPct,
                    double level, double invalidation, LocalTime triggerTime) {
            this.symbol        = symbol;
            this.date          = date;
            this.side          = side;
            this.entryPrice    = entryPrice;
            this.exitPrice     = exitPrice;
            this.pnlPct        = pnlPct;
            this.level         = level;
            this.invalidation  = invalidation;
            this.triggerTime   = triggerTime;
        }
    }

    public static class BacktestSummary {
        public final LocalDate from, to;
        public final double stopLossPct;
        public final int totalTrades, wins, losses;
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