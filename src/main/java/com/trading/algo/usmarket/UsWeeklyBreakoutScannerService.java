package com.trading.algo.usmarket;

import com.trading.algo.dtos.UsWeeklyBreakoutState;import com.trading.algo.dtos.UsWeeklyBreakoutStateStore;import com.trading.algo.entity.UsCandle;import com.trading.algo.telegram.TelegramService;import jakarta.annotation.PostConstruct;import org.slf4j.Logger;import org.slf4j.LoggerFactory;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Service;

import java.io.BufferedReader;import java.io.InputStreamReader;import java.time.DayOfWeek;import java.time.LocalDate;import java.util.ArrayList;import java.util.Collections;import java.util.List;import java.util.Map;import java.util.concurrent.CopyOnWriteArrayList;

/**

 US Weekly Breakout Scanner — ATH/52-week high stocks only



 Ticker source (in priority order):



 In-memory list — populated via POST /us-weekly/upload-tickers



 Paste tickers directly from WSJ 52-week high page, no file needed.



 sp500.csv on classpath (src/main/resources/sp500.csv)



 Updated manually each Friday after reviewing WSJ.



 Workflow:

 Friday  → POST /us-weekly/upload-tickers (paste WSJ list)

 Monday 6:00 AM IST → seedPreviousWeekRange() auto-runs

 Mon–Fri 10:00 AM IST → scanDailyClose() after US market close*/
@Service
public class UsWeeklyBreakoutScannerService {

    private static final Logger log = LoggerFactory.getLogger(UsWeeklyBreakoutScannerService.class);

    private static final String DEFAULT_TICKER_CSV = "sp500.csv";

    private static final double MIN_RANGE_PCT         = 1.5;
    private static final double MAX_RANGE_PCT         = 8.0;
    private static final double MIN_VOLUME_MULTIPLIER = 1.5;

    private final CopyOnWriteArrayList<String> uploadedTickers = new CopyOnWriteArrayList<>();

    private final UsMarketDataService        marketDataService;
    private final UsWeeklyBreakoutStateStore stateStore;
    private final TelegramService            telegramService;

    public UsWeeklyBreakoutScannerService(UsMarketDataService marketDataService,
                                          UsWeeklyBreakoutStateStore stateStore,
                                          TelegramService telegramService) {
        this.marketDataService = marketDataService;
        this.stateStore        = stateStore;
        this.telegramService   = telegramService;
    }

    // ── Startup ──────────────────────────────────────────────────────────────
    @PostConstruct
    public void seedOnStartupIfNeeded() {
        log.info("[US-WEEKLY] Startup: storeSize={} — no seed needed", stateStore.size());
    }

    // ── Upload tickers from controller ───────────────────────────────────────
    public void uploadAndSeed(List<String> tickers) {
        uploadedTickers.clear();
        for (String t : tickers) {
            String cleaned = t.trim().toUpperCase();
            if (!cleaned.isEmpty() && !cleaned.startsWith("#")) {
                String ticker = cleaned.split(",")[0].trim();
                if (!ticker.isEmpty()) uploadedTickers.add(ticker);
            }
        }
        log.info("[US-WEEKLY] Uploaded {} tickers via API — seeding now", uploadedTickers.size());
        seedPreviousWeekRange();
    }

    // ── Monday 6:00 AM IST: seed previous week's range ───────────────────────
    @Scheduled(cron = "0 0 6 * * MON", zone = "Asia/Kolkata")
    public void seedPreviousWeekRange() {
        log.info("[US-WEEKLY] Seeding previous week range...");
        stateStore.clear();

        List<String> tickers = resolveTickers();
        if (tickers.isEmpty()) {
            log.warn("[US-WEEKLY] No tickers to seed. Use POST /us-weekly/upload-tickers or add {} to resources/", DEFAULT_TICKER_CSV);
            return;
        }
        log.info("[US-WEEKLY] Seeding {} tickers from {} — using batch fetch", tickers.size(), tickerSource());

        // Batch fetch: 275 tickers → 35 API calls (8 tickers/call) instead of 275
        Map<String, List<UsCandle>> weeklyBatch = marketDataService.fetchWeeklyBatch(tickers, 3);

        int seeded = 0, skipped = 0;
        for (String ticker : tickers) {
            try {
                List<UsCandle> weekly = weeklyBatch.getOrDefault(ticker, Collections.emptyList());
                if (weekly.size() < 2) {
                    // log.debug("[US-WEEKLY] {} skipped — insufficient weekly data", ticker);

                    log.info("[US-WEEKLY] {} skipped — insufficient weekly data (got {} candles)", ticker, weekly.size());

                    skipped++;
                    continue;
                }

                UsCandle prevWeek = weekly.get(weekly.size() - 2);
                double wHigh   = prevWeek.getHigh();
                double wLow    = prevWeek.getLow();
                double wOpen   = prevWeek.getOpen();
                double wClose  = prevWeek.getClose();
                long   wVolume = prevWeek.getVolume();

                double rangeWidth = ((wHigh - wLow) / wLow) * 100.0;
                if (rangeWidth < MIN_RANGE_PCT || rangeWidth > MAX_RANGE_PCT) {
                    log.info("[US-WEEKLY] {} skipped — range {:.2f}% (allowed {}-{}%)",
                            ticker, rangeWidth, MIN_RANGE_PCT, MAX_RANGE_PCT);
                    skipped++;
                    continue;
                }

                stateStore.put(ticker, UsWeeklyBreakoutState.builder()
                        .ticker(ticker)
                        .weeklyHigh(wHigh)
                        .weeklyLow(wLow)
                        .weeklyOpen(wOpen)
                        .weeklyVolume(wVolume)
                        .buyAlerted(false)
                        .sellAlerted(false)
                        .prevDailyHigh(wHigh)
                        .prevDailyLow(wLow)
                        .prevWeekClose(wClose)
                        .weekStartOpen(wOpen)
                        .is52WeekHigh(true)
                        .build());
                seeded++;

            } catch (Exception e) {
                log.error("[US-WEEKLY] Seed error for {}: {}", ticker, e.getMessage());
                skipped++;
            }
        }
        log.info("[US-WEEKLY] Seeded {} tickers ({} skipped).", seeded, skipped);
    }

    // ── Mon–Fri 10:00 AM IST: scan daily close ────────────────────────────────
    @Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanDailyClose() {
        if (stateStore.size() == 0) {
            log.warn("[US-WEEKLY] State store empty — upload tickers via POST /us-weekly/upload-tickers");
            return;
        }

        log.info("[US-WEEKLY] Daily close scan — {} tickers watched", stateStore.size());

        List<String> activeTickers = new ArrayList<>();
        for (UsWeeklyBreakoutState s : stateStore.all()) {
            if (!s.isBuyAlerted() || !s.isSellAlerted()) activeTickers.add(s.getTicker());
        }

        // Batch fetch daily candles — all active tickers in one pass
        Map<String, List<UsCandle>> dailyBatch = marketDataService.fetchDailyBatch(activeTickers, 2);

        int buyFired = 0, sellFired = 0;
        for (String ticker : activeTickers) {
            UsWeeklyBreakoutState state = stateStore.get(ticker);
            if (state == null) continue;

            List<UsCandle> daily = dailyBatch.getOrDefault(ticker, Collections.emptyList());
            if (daily.isEmpty()) continue;

            UsCandle today = daily.get(daily.size() - 1);
            double dHigh   = today.getHigh();
            double dLow    = today.getLow();
            double dClose  = today.getClose();
            long   dVolume = today.getVolume();
            long   avgDVol = state.getWeeklyVolume() / 5;

            if (!state.isBuyAlerted()) {
                if (dClose > state.getWeeklyHigh()) {
                    if (dVolume >= (long)(avgDVol * MIN_VOLUME_MULTIPLIER)) {
                        state.setWeeklyLow(state.getPrevDailyLow());
                        sendBuyAlert(state, today);
                        state.setBuyAlerted(true);
                        buyFired++;
                    } else {
                        log.debug("[US-WEEKLY] {} BUY skipped — low volume", ticker);
                    }
                } else if (dHigh > state.getWeeklyHigh()) {
                    state.setWeeklyHigh(dHigh);
                }
            }

            if (!state.isSellAlerted()) {
                if (dClose < state.getWeeklyLow()) {
                    if (dVolume >= (long)(avgDVol * MIN_VOLUME_MULTIPLIER)) {
                        state.setWeeklyHigh(state.getPrevDailyHigh());
                        sendSellAlert(state, today);
                        state.setSellAlerted(true);
                        sellFired++;
                    } else {
                        log.debug("[US-WEEKLY] {} SELL skipped — low volume", ticker);
                    }
                } else if (dLow < state.getWeeklyLow()) {
                    state.setWeeklyLow(dLow);
                }
            }

            state.setPrevDailyHigh(dHigh);
            state.setPrevDailyLow(dLow);
        }
        log.info("[US-WEEKLY] Scan done → BUY:{} SELL:{}", buyFired, sellFired);
    }

    // ── Manual triggers ───────────────────────────────────────────────────────
    public void triggerManualSeed() { seedPreviousWeekRange(); }
    public void triggerManualScan() { scanDailyClose(); }

    /**
     * Replays the full current week (Mon–Fri) day by day using batch fetch.
     * Use GET /us-weekly/scan-week when the app was down during the week.
     */
    public int[] scanWeek() {
        if (stateStore.size() == 0) {
            log.warn("[US-WEEKLY] scan-week: state store empty — seed first via POST /us-weekly/upload-tickers");
            return new int[]{0, 0};
        }

        LocalDate today  = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        List<LocalDate> weekDays = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocalDate d = monday.plusDays(i);
            if (!d.isAfter(today)) weekDays.add(d);
        }
        log.info("[US-WEEKLY] scan-week: replaying {} days ({} → {})",
                weekDays.size(), weekDays.get(0), weekDays.get(weekDays.size() - 1));

        List<String> activeTickers = new ArrayList<>();
        for (UsWeeklyBreakoutState s : stateStore.all()) {
            if (!s.isBuyAlerted() || !s.isSellAlerted()) activeTickers.add(s.getTicker());
        }

        // Batch fetch 7 daily candles for all tickers in one pass
        Map<String, List<UsCandle>> dailyBatch = marketDataService.fetchDailyBatch(activeTickers, 7);

        int totalBuy = 0, totalSell = 0;
        for (String ticker : activeTickers) {
            UsWeeklyBreakoutState state = stateStore.get(ticker);
            if (state == null) continue;

            List<UsCandle> daily = dailyBatch.getOrDefault(ticker, Collections.emptyList());
            if (daily.isEmpty()) continue;

            for (LocalDate day : weekDays) {
                if (state.isBuyAlerted() && state.isSellAlerted()) break;

                UsCandle candle = daily.stream()
                        .filter(c -> c.getDate().equals(day))
                        .findFirst().orElse(null);
                if (candle == null) continue;

                double dHigh   = candle.getHigh();
                double dLow    = candle.getLow();
                double dClose  = candle.getClose();
                long   dVolume = candle.getVolume();
                long   avgDVol = state.getWeeklyVolume() / 5;

                if (!state.isBuyAlerted()) {
                    if (dClose > state.getWeeklyHigh()) {
                        if (dVolume >= (long)(avgDVol * MIN_VOLUME_MULTIPLIER)) {
                            state.setWeeklyLow(state.getPrevDailyLow());
                            sendBuyAlert(state, candle);
                            state.setBuyAlerted(true);
                            totalBuy++;
                        }
                    } else if (dHigh > state.getWeeklyHigh()) {
                        state.setWeeklyHigh(dHigh);
                    }
                }

                if (!state.isSellAlerted()) {
                    if (dClose < state.getWeeklyLow()) {
                        if (dVolume >= (long)(avgDVol * MIN_VOLUME_MULTIPLIER)) {
                            state.setWeeklyHigh(state.getPrevDailyHigh());
                            sendSellAlert(state, candle);
                            state.setSellAlerted(true);
                            totalSell++;
                        }
                    } else if (dLow < state.getWeeklyLow()) {
                        state.setWeeklyLow(dLow);
                    }
                }

                state.setPrevDailyHigh(dHigh);
                state.setPrevDailyLow(dLow);
            }
        }

        log.info("[US-WEEKLY] scan-week done → BUY:{} SELL:{}", totalBuy, totalSell);
        return new int[]{totalBuy, totalSell};
    }

    // ── Ticker resolution ────────────────────────────────────────────────────
    private List<String> resolveTickers() {
        if (!uploadedTickers.isEmpty()) return new ArrayList<>(uploadedTickers);
        return loadTickersFromCsv();
    }

    private String tickerSource() {
        return uploadedTickers.isEmpty() ? DEFAULT_TICKER_CSV : "uploaded list";
    }

    private List<String> loadTickersFromCsv() {
        List<String> tickers = new ArrayList<>();
        try {
            var stream = getClass().getClassLoader().getResourceAsStream(DEFAULT_TICKER_CSV);
            if (stream == null) {
                log.warn("[US-WEEKLY] {} not found on classpath.", DEFAULT_TICKER_CSV);
                return tickers;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String ticker = line.split(",")[0].trim().toUpperCase();
                if (!ticker.isEmpty()) tickers.add(ticker);
            }
            reader.close();
            log.info("[US-WEEKLY] Loaded {} tickers from {}", tickers.size(), DEFAULT_TICKER_CSV);
        } catch (Exception e) {
            log.error("[US-WEEKLY] Failed to load {}: {}", DEFAULT_TICKER_CSV, e.getMessage());
        }
        return tickers;
    }

    // ── Telegram alerts ───────────────────────────────────────────────────────
    private void sendBuyAlert(UsWeeklyBreakoutState state, UsCandle candle) {
        double close     = candle.getClose();
        double slLevel   = state.getPrevDailyLow();
        double riskPct   = ((close - slLevel) / close) * 100.0;
        double movePct   = state.getWeekStartOpen() > 0
                ? ((close - state.getWeekStartOpen()) / state.getWeekStartOpen()) * 100.0 : 0;
        double prevWkChg = state.getPrevWeekClose() > 0
                ? ((close - state.getPrevWeekClose()) / state.getPrevWeekClose()) * 100.0 : 0;

        telegramService.sendMessage(String.format(
                "🇺🇸🟢 *US WEEKLY BUY BREAKOUT*%n" +
                        "📌 *%s*%n" +
                        "────────────────%n" +
                        "📈 Daily Close:      $%.2f%n" +
                        "🎯 Above level:      $%.2f%n" +
                        "🛑 SL:               $%.2f  (%.2f%% risk)%n" +
                        "📊 Move this week:   %s%.2f%%%n" +
                        "📅 Prev week close:  $%.2f  (%s%.2f%%)%n" +
                        "📦 Day High/Low:     $%.2f / $%.2f%n" +
                        "📊 Volume:           %,d%n" +
                        "🏆 52-Week High:     ✅ Yes%n" +
                        "📅 Date (EST):       %s",
                state.getTicker(),
                close, state.getWeeklyHigh(),
                slLevel, riskPct,
                movePct >= 0 ? "+" : "", movePct,
                state.getPrevWeekClose(), prevWkChg >= 0 ? "+" : "", prevWkChg,
                candle.getHigh(), candle.getLow(),
                candle.getVolume(),
                candle.getDate()));

        log.info("[US-WEEKLY] 🟢 BUY  {} @ ${} | weeklyH ${} | SL ${} ({}% risk)",
                state.getTicker(), close, state.getWeeklyHigh(), slLevel,
                String.format("%.2f", riskPct));
    }

    private void sendSellAlert(UsWeeklyBreakoutState state, UsCandle candle) {
        double close     = candle.getClose();
        double slLevel   = state.getPrevDailyHigh();
        double riskPct   = ((slLevel - close) / close) * 100.0;
        double movePct   = state.getWeekStartOpen() > 0
                ? ((close - state.getWeekStartOpen()) / state.getWeekStartOpen()) * 100.0 : 0;
        double prevWkChg = state.getPrevWeekClose() > 0
                ? ((close - state.getPrevWeekClose()) / state.getPrevWeekClose()) * 100.0 : 0;

        telegramService.sendMessage(String.format(
                "🇺🇸🔴 *US WEEKLY SELL BREAKDOWN*%n" +
                        "📌 *%s*%n" +
                        "────────────────%n" +
                        "📉 Daily Close:      $%.2f%n" +
                        "🎯 Below level:      $%.2f%n" +
                        "🛑 SL:               $%.2f  (%.2f%% risk)%n" +
                        "📊 Move this week:   %s%.2f%%%n" +
                        "📅 Prev week close:  $%.2f  (%s%.2f%%)%n" +
                        "📦 Day High/Low:     $%.2f / $%.2f%n" +
                        "📊 Volume:           %,d%n" +
                        "📅 Date (EST):       %s",
                state.getTicker(),
                close, state.getWeeklyLow(),
                slLevel, riskPct,
                movePct >= 0 ? "+" : "", movePct,
                state.getPrevWeekClose(), prevWkChg >= 0 ? "+" : "", prevWkChg,
                candle.getHigh(), candle.getLow(),
                candle.getVolume(),
                candle.getDate()));

        log.info("[US-WEEKLY] 🔴 SELL {} @ ${} | weeklyL ${} | SL ${} ({}% risk)",
                state.getTicker(), close, state.getWeeklyLow(), slLevel,
                String.format("%.2f", riskPct));
    }}