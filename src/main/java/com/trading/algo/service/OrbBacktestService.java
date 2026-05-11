package com.trading.algo.service;


import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.trading.algo.config.OrbConfig;

@Service
public class OrbBacktestService {

    private static final Logger log = LoggerFactory.getLogger(OrbBacktestService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 15);
    private static final LocalTime SCAN_START   = LocalTime.of(9, 46);

    private final OrbConfig orbConfig;
    private final RestTemplate restTemplate;
    private final TelegramService telegramService;

    public OrbBacktestService(OrbConfig orbConfig,
                              RestTemplate restTemplate,
                              TelegramService telegramService) {
        this.orbConfig = orbConfig;
        this.restTemplate = restTemplate;
        this.telegramService = telegramService;
    }

    // ================== ASYNC ENTRY ==================
    @Async
    public void runBacktestAsync(LocalDate from, LocalDate to, double stopLossPct, String requestId) {
        try {
            log.info("[ORB-BT][{}] Started...", requestId);

            BacktestSummary summary = run(from, to, stopLossPct, requestId);

            log.info("[ORB-BT][{}] Completed. Trades: {}", requestId, summary.totalTrades);

        } catch (Exception e) {
            log.error("[ORB-BT][{}] Failed: {}", requestId, e.getMessage(), e);

            telegramService.sendMessage(
                "❌ ORB Backtest Failed\nRequestId: " + requestId + "\nError: " + e.getMessage()
            );
        }
    }

    // ================== CORE LOGIC ==================
    public BacktestSummary run(LocalDate fromDate, LocalDate toDate, double stopLossPct, String requestId) {

        log.info("[ORB-BT][{}] Running: {} → {} | SL: {}%", requestId, fromDate, toDate, stopLossPct);

        List<TradeResult> allTrades = Collections.synchronizedList(new ArrayList<>());
        Map<String, String> keyToSymbol = orbConfig.getKeyToSymbolMap();

        List<LocalDate> tradingDays = getTradingDays(fromDate, toDate);

        AtomicInteger processed = new AtomicInteger();
        int totalSymbols = keyToSymbol.size();

        // 🚀 PARALLEL EXECUTION
        keyToSymbol.entrySet().parallelStream().forEach(entry -> {

            String instrumentKey = entry.getKey();
            String symbol = entry.getValue();

            int count = processed.incrementAndGet();
            if (count % 50 == 0) {
                log.info("[ORB-BT][{}] Progress: {}/{}", requestId, count, totalSymbols);
            }

            for (LocalDate date : tradingDays) {
                try {
                    List<Candle> candles = fetchDayCandles(instrumentKey, date);
                    if (candles == null || candles.size() < 2) continue;

                    List<TradeResult> trades = evaluateDay(symbol, date, candles, stopLossPct);
                    allTrades.addAll(trades);

                    Thread.sleep(10); // 🛑 rate-limit safety

                } catch (Exception e) {
                    log.debug("[ORB-BT][{}] Skip {} {}: {}", requestId, symbol, date, e.getMessage());
                }
            }
        });

        BacktestSummary summary = buildSummary(allTrades, fromDate, toDate, stopLossPct);
        sendResultsToTelegram(summary, allTrades, fromDate, toDate, stopLossPct, requestId);

        return summary;
    }

    // ================== STRATEGY ==================
    private List<TradeResult> evaluateDay(String symbol, LocalDate date,
                                          List<Candle> candles, double stopLossPct) {

        List<TradeResult> trades = new ArrayList<>();

        Candle first = candles.get(0);
        if (!first.time.toLocalTime().equals(MARKET_OPEN)) return trades;

        double rollingHigh = first.high;
        double rollingLow  = first.low;

        boolean buyTriggered = false;
        boolean sellTriggered = false;

        for (int i = 1; i < candles.size(); i++) {
            Candle c = candles.get(i);

            if (c.time.toLocalTime().isBefore(SCAN_START)) {
                rollingHigh = Math.max(rollingHigh, c.high);
                rollingLow  = Math.min(rollingLow, c.low);
                continue;
            }

            // BUY
            if (!buyTriggered && c.close > rollingHigh) {
                buyTriggered = true;

                double entry = c.close;
                double sl = entry * (1 - stopLossPct / 100);

                double exit = findExit(candles, i + 1, sl, Double.MAX_VALUE, "BUY");
                double pnl = ((exit - entry) / entry) * 100;

                trades.add(new TradeResult(symbol, date, "BUY",
                        entry, exit, pnl, rollingHigh, c.time.toLocalTime()));

            } else if (!buyTriggered) {
                rollingHigh = Math.max(rollingHigh, c.high);
            }

            // SELL
            if (!sellTriggered && c.close < rollingLow) {
                sellTriggered = true;

                double entry = c.close;
                double sl = entry * (1 + stopLossPct / 100);

                double exit = findExit(candles, i + 1, Double.MIN_VALUE, sl, "SELL");
                double pnl = ((entry - exit) / entry) * 100;

                trades.add(new TradeResult(symbol, date, "SELL",
                        entry, exit, pnl, rollingLow, c.time.toLocalTime()));

            } else if (!sellTriggered) {
                rollingLow = Math.min(rollingLow, c.low);
            }

            if (buyTriggered && sellTriggered) break;
        }

        return trades;
    }

    // ================== EXIT ==================
    private double findExit(List<Candle> candles, int fromIdx,
                            double buySL, double sellSL, String side) {

        for (int i = fromIdx; i < candles.size(); i++) {
            Candle c = candles.get(i);

            if ("BUY".equals(side) && c.low <= buySL) return buySL;
            if ("SELL".equals(side) && c.high >= sellSL) return sellSL;

            if (!c.time.toLocalTime().isBefore(MARKET_CLOSE)) return c.close;
        }

        return candles.get(candles.size() - 1).close;
    }

    // ================== API ==================
    private List<Candle> fetchDayCandles(String instrumentKey, LocalDate date) {

        String dateStr = date.format(DATE_FMT);
        String url = String.format(
                "https://api.upstox.com/v2/historical-candle/%s/1minute/%s/%s",
                instrumentKey, dateStr, dateStr
        );

        Map<String, Object> response = fetchWithRetry(url);
        if (response == null || !"success".equals(response.get("status"))) return null;

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return null;

        List<List<Object>> raw = (List<List<Object>>) data.get("candles");
        if (raw == null || raw.isEmpty()) return null;

        List<Candle> candles = new ArrayList<>();
        for (List<Object> c : raw) {
            String ts = c.get(0).toString().substring(0, 19);

            candles.add(new Candle(
                    LocalDateTime.parse(ts, DT_FMT),
                    toDouble(c.get(1)),
                    toDouble(c.get(2)),
                    toDouble(c.get(3)),
                    toDouble(c.get(4)),
                    toLong(c.get(5))
            ));
        }

        Collections.reverse(candles);
        return candles;
    }

    private Map<String, Object> fetchWithRetry(String url) {
        for (int i = 0; i < 3; i++) {
            try {
                return restTemplate.getForObject(url, Map.class);
            } catch (Exception e) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    // ================== SUMMARY ==================
    private BacktestSummary buildSummary(List<TradeResult> trades,
                                         LocalDate from, LocalDate to, double slPct) {

        if (trades.isEmpty()) {
            return new BacktestSummary(from, to, slPct, 0,0,0,0,0,0,0,0);
        }

        long wins = trades.stream().filter(t -> t.pnlPct > 0).count();
        long losses = trades.size() - wins;

        double total = trades.stream().mapToDouble(t -> t.pnlPct).sum();
        double avg = total / trades.size();

        double best = trades.stream().mapToDouble(t -> t.pnlPct).max().orElse(0);
        double worst = trades.stream().mapToDouble(t -> t.pnlPct).min().orElse(0);

        double winRate = (wins * 100.0) / trades.size();

        return new BacktestSummary(from, to, slPct,
                trades.size(), (int) wins, (int) losses,
                winRate, avg, best, worst, 0);
    }

    // ================== TELEGRAM ==================
    private void sendResultsToTelegram(BacktestSummary summary,
                                      List<TradeResult> trades,
                                      LocalDate from, LocalDate to,
                                      double slPct, String requestId) {

        telegramService.sendMessage(
                "📊 ORB Backtest\n🆔 " + requestId +
                        "\nTrades: " + summary.totalTrades +
                        "\nWinRate: " + String.format("%.2f", summary.winRate) + "%"
        );

        telegramService.sendDocument(
                buildCsv(trades),
                "orb_bt.csv",
                "Backtest " + requestId
        );
    }

    // ================== CSV ==================
    private byte[] buildCsv(List<TradeResult> trades) {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol,Date,Side,Entry,Exit,PnL\n");

        for (TradeResult t : trades) {
            sb.append(String.format("%s,%s,%s,%.2f,%.2f,%.2f\n",
                    t.symbol, t.date, t.side,
                    t.entryPrice, t.exitPrice, t.pnlPct));
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ================== HELPERS ==================
    private List<LocalDate> getTradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> list = new ArrayList<>();
        LocalDate d = from;

        while (!d.isAfter(to)) {
            if (!(d.getDayOfWeek() == DayOfWeek.SATURDAY ||
                  d.getDayOfWeek() == DayOfWeek.SUNDAY)) {
                list.add(d);
            }
            d = d.plusDays(1);
        }
        return list;
    }

    private double toDouble(Object o) {
        return ((Number) o).doubleValue();
    }

    private long toLong(Object o) {
        return ((Number) o).longValue();
    }

    // ================== MODELS ==================
    private static class Candle {
        LocalDateTime time;
        double open, high, low, close;
        long volume;

        Candle(LocalDateTime t, double o, double h, double l, double c, long v) {
            time=t; open=o; high=h; low=l; close=c; volume=v;
        }
    }

    public static class TradeResult {
        public final String symbol;
        public final LocalDate date;
        public final String side;
        public final double entryPrice;
        public final double exitPrice;
        public final double pnlPct;
        public final double level;
        public final LocalTime triggerTime;

        TradeResult(String s, LocalDate d, String side,
                    double e, double ex, double pnl,
                    double lvl, LocalTime t) {
            this.symbol=s; this.date=d; this.side=side;
            this.entryPrice=e; this.exitPrice=ex;
            this.pnlPct=pnl; this.level=lvl; this.triggerTime=t;
        }
    }

    public static class BacktestSummary {
        public final LocalDate from, to;
        public final double stopLossPct;
        public final int totalTrades, wins, losses;
        public final double winRate, avgPnl, bestTrade, worstTrade, maxDrawdown;

        BacktestSummary(LocalDate f, LocalDate t, double sl,
                        int tt, int w, int l,
                        double wr, double ap,
                        double bt, double wt, double dd) {
            from=f; to=t; stopLossPct=sl;
            totalTrades=tt; wins=w; losses=l;
            winRate=wr; avgPnl=ap;
            bestTrade=bt; worstTrade=wt;
            maxDrawdown=dd;
        }
    }
}