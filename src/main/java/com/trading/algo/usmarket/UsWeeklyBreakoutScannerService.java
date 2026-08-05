package com.trading.algo.usmarket;

import com.trading.algo.discord.DiscordService;
import com.trading.algo.dtos.UsWeeklyBreakoutState;
import com.trading.algo.dtos.UsWeeklyBreakoutStateStore;
import com.trading.algo.entity.UsCandle;
import com.trading.algo.telegram.TelegramService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private final DiscordService              discordService;

    public UsWeeklyBreakoutScannerService(UsMarketDataService marketDataService,
                                          UsWeeklyBreakoutStateStore stateStore,
                                          TelegramService telegramService,
                                          DiscordService discordService) {
        this.marketDataService = marketDataService;
        this.stateStore        = stateStore;
        this.telegramService   = telegramService;
        this.discordService    = discordService;
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

                // Log all weekly candles to debug the indexing issue
                log.info("[US-WEEKLY][SEED-DEBUG] {} → Weekly candles ({} total):", ticker, weekly.size());
                for (int i = 0; i < weekly.size(); i++) {
                    UsCandle c = weekly.get(i);
                    log.info("[US-WEEKLY][SEED-DEBUG]   [{}] Date={} High={} Low={} Close={}",
                            i, c.getDate(), c.getHigh(), c.getLow(), c.getClose());
                }

                // Skip the last 2 candles (current week + malformed candle) to get the actual previous week
                UsCandle prevWeek = weekly.get(weekly.size() - 3);
                double wHigh   = prevWeek.getHigh();
                double wLow    = prevWeek.getLow();
                double wOpen   = prevWeek.getOpen();
                double wClose  = prevWeek.getClose();
                long   wVolume = prevWeek.getVolume();
                log.info("[US-WEEKLY][SEED] {} → PrevWeek | High={} Low={} Open={} Close={} Volume={}",
                        ticker, wHigh, wLow, wOpen, wClose, wVolume);
                // Filter: weekly range 1.5% – 8%
                // The core idea here is to find a "sweet spot" for volatility:
                // - MIN_RANGE_PCT (1.5%): Filters out stocks with very low volatility,
                //   which often lack momentum for a significant breakout. These might be
                //   "dead money" or illiquid.
                // - MAX_RANGE_PCT (8.0%): Filters out stocks that have already made
                //   very large moves in the previous week. These are considered
                //   "overextended" and might be due for a pullback or consolidation,
                //   offering a poor risk-reward for a new breakout entry.

//                double rangeWidth = ((wHigh - wLow) / wLow) * 100.0;//                if (rangeWidth < MIN_RANGE_PCT || rangeWidth > MAX_RANGE_PCT) {//                    log.info("[US-WEEKLY] {} skipped — range {:.2f}% (allowed {}-{}%)",//                            ticker, rangeWidth, MIN_RANGE_PCT, MAX_RANGE_PCT);//                    skipped++;//                    continue;//                }

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
        // History is used only to label an already-confirmed breakout.
        // It is not part of the breakout decision itself.
        Map<String, List<UsCandle>> dailyBatch = marketDataService.fetchDailyBatch(activeTickers, 11);
        Map<String, List<UsCandle>> weeklyBatch = marketDataService.fetchWeeklyBatch(activeTickers, 12);

        List<String> buyMessages  = new ArrayList<>();
        List<String> sellMessages = new ArrayList<>();
        int buyFired = 0, sellFired = 0;
        for (String ticker : activeTickers) {
            UsWeeklyBreakoutState state = stateStore.get(ticker);
            if (state == null) continue;

            List<UsCandle> daily = dailyBatch.getOrDefault(ticker, Collections.emptyList());
            if (daily.isEmpty()) continue;

            double currentWeeklyClose = daily.get(daily.size() - 1).getClose();
            log.info("[US-WEEKLY][WEEKLY-DATA] {} → CurrentWeekClose calculated from daily: {}",
                    ticker, currentWeeklyClose);

            UsCandle today = daily.get(daily.size() - 1);
            double dHigh   = today.getHigh();
            double dLow    = today.getLow();
            double dClose  = today.getClose();
            long   dVolume = today.getVolume();
            long   avgDVol = state.getWeeklyVolume() / 5;

            log.info("[US-WEEKLY][SCAN] {} → Close={} High={} Low={} | WeeklyHigh={} WeeklyLow={} | CurrentWeeklyClose={}",
                    ticker, dClose, dHigh, dLow,
                    state.getWeeklyHigh(), state.getWeeklyLow(), currentWeeklyClose);

            if (!state.isBuyAlerted()) {
                if (dClose > state.getWeeklyHigh() && currentWeeklyClose > state.getWeeklyHigh()) {
                        state.setWeeklyLow(state.getPrevDailyLow());
                        String msg = buildBuyAlert(state, today, daily,
                                weeklyBatch.getOrDefault(ticker, Collections.emptyList()));
                        telegramService.sendMessageToUs(msg);
                        buyMessages.add(msg);
                        state.setBuyAlerted(true);
                        buyFired++;
                } else if (dHigh > state.getWeeklyHigh()) {
                    state.setWeeklyHigh(dHigh);
                }
            }

            if (!state.isSellAlerted()) {
                if (dClose < state.getWeeklyLow()) {
                        state.setWeeklyHigh(state.getPrevDailyHigh());
                        String msg = buildSellAlert(state, today);
                        telegramService.sendMessageToUs(msg);
                        sellMessages.add(msg);
                        state.setSellAlerted(true);
                        sellFired++;
                } else if (dLow < state.getWeeklyLow()) {
                    state.setWeeklyLow(dLow);
                }
            }

            state.setPrevDailyHigh(dHigh);
            state.setPrevDailyLow(dLow);
        }

        // Send all BUY alerts as one Discord message, SELL alerts as another
        if (!buyMessages.isEmpty())  discordService.sendMessage(String.join("\n\n", buyMessages));
        if (!sellMessages.isEmpty()) discordService.sendMessage(String.join("\n\n", sellMessages));

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

        // History is used only to label an already-confirmed breakout.
        // It is not part of the breakout decision itself.
        Map<String, List<UsCandle>> dailyBatch = marketDataService.fetchDailyBatch(activeTickers, 11);
        Map<String, List<UsCandle>> weeklyBatch = marketDataService.fetchWeeklyBatch(activeTickers, 12);

        List<String> buyMessages  = new ArrayList<>();
        List<String> sellMessages = new ArrayList<>();
        int totalBuy = 0, totalSell = 0;
        for (String ticker : activeTickers) {
            UsWeeklyBreakoutState state = stateStore.get(ticker);
            if (state == null) continue;

            List<UsCandle> daily = dailyBatch.getOrDefault(ticker, Collections.emptyList());
            if (daily.isEmpty()) continue;

            double currentWeeklyClose = daily.get(daily.size() - 1).getClose();
            log.info("[US-WEEKLY][WEEKLY-DATA] {} → CurrentWeekClose calculated from daily: {}",
                    ticker, currentWeeklyClose);

            for (LocalDate day : weekDays) {
                if (state.isBuyAlerted() && state.isSellAlerted()) break;

                UsCandle candle = daily.stream()
                        .filter(c -> c.getDate().equals(day))
                        .findFirst().orElse(null);
                if (candle == null) continue;

                double dHigh  = candle.getHigh();
                double dLow   = candle.getLow();
                double dClose = candle.getClose();

                log.info("[US-WEEKLY][SCAN-WEEK] {} → Date={} Close={} High={} Low={} | WeeklyHigh={} WeeklyLow={} | CurrentWeeklyClose={}",
                        ticker, candle.getDate(), dClose, dHigh, dLow,
                        state.getWeeklyHigh(), state.getWeeklyLow(), currentWeeklyClose);

                if (!state.isBuyAlerted()) {
                    if (dClose > state.getWeeklyHigh() && currentWeeklyClose > state.getWeeklyHigh()) {
                        state.setWeeklyLow(state.getPrevDailyLow());
                        String msg = buildBuyAlert(state, candle, daily,
                                weeklyBatch.getOrDefault(ticker, Collections.emptyList()));
                        telegramService.sendMessageToUs(msg);
                        buyMessages.add(msg);
                        state.setBuyAlerted(true);
                        totalBuy++;
                    } else if (dHigh > state.getWeeklyHigh()) {
                        state.setWeeklyHigh(dHigh);
                    }
                }

                if (!state.isSellAlerted()) {
                    if (dClose < state.getWeeklyLow()) {
                        state.setWeeklyHigh(state.getPrevDailyHigh());
                        String msg = buildSellAlert(state, candle);
                        telegramService.sendMessageToUs(msg);
                        sellMessages.add(msg);
                        state.setSellAlerted(true);
                        totalSell++;
                    } else if (dLow < state.getWeeklyLow()) {
                        state.setWeeklyLow(dLow);
                    }
                }

                state.setPrevDailyHigh(dHigh);
                state.setPrevDailyLow(dLow);
            }
        }

        if (!buyMessages.isEmpty())  discordService.sendMessage(String.join("\n\n", buyMessages));
        if (!sellMessages.isEmpty()) discordService.sendMessage(String.join("\n\n", sellMessages));

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

    // ── Alert builders ────────────────────────────────────────────────────────
    private String buildBuyAlert(UsWeeklyBreakoutState state, UsCandle candle,
                                 List<UsCandle> dailyCandles, List<UsCandle> weeklyCandles) {
        double close     = candle.getClose();
        double slLevel   = state.getPrevDailyLow();
        double riskPct   = ((close - slLevel) / close) * 100.0;
        double movePct   = state.getWeekStartOpen() > 0
                ? ((close - state.getWeekStartOpen()) / state.getWeekStartOpen()) * 100.0 : 0;
        double prevWkChg = state.getPrevWeekClose() > 0
                ? ((close - state.getPrevWeekClose()) / state.getPrevWeekClose()) * 100.0 : 0;

        log.info("[US-WEEKLY] 🟢 BUY  {} @ ${} | weeklyH ${} | SL ${} ({}% risk)",
                state.getTicker(), close, state.getWeeklyHigh(), slLevel,
                String.format("%.2f", riskPct));

        String alert = String.format(
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
                candle.getDate());
        BreakoutClassification classification = classifyBreakout(candle, dailyCandles, weeklyCandles);
        return alert + String.format("%n%nCategories: %s%nWeekly body: %.1f%%",
                classification.labels(), classification.bodyPct());
    }

    /**
     * Presentation-only labels for an already-confirmed BUY breakout. These values
     * never participate in the strategy's entry or state-transition logic.
     */
    private BreakoutClassification classifyBreakout(UsCandle breakoutCandle, List<UsCandle> dailyCandles,
                                                    List<UsCandle> weeklyCandles) {
        LocalDate signalDate = breakoutCandle.getDate();
        LocalDate weekStart = signalDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        List<UsCandle> completedWeeklyHistory = weeklyCandles.stream()
                .filter(c -> c.getDate().isBefore(weekStart))
                .sorted(Comparator.comparing(UsCandle::getDate))
                .toList();
        List<UsCandle> lastTenWeekly = completedWeeklyHistory.size() > 10
                ? completedWeeklyHistory.subList(completedWeeklyHistory.size() - 10, completedWeeklyHistory.size())
                : completedWeeklyHistory;
        boolean hasTenWeeklyCandles = lastTenWeekly.size() == 10;
        boolean momentumPass = hasTenWeeklyCandles
                && lastTenWeekly.stream().allMatch(c -> breakoutCandle.getClose() > c.getClose());

        List<UsCandle> currentWeek = dailyCandles.stream()
                .filter(c -> !c.getDate().isBefore(weekStart) && !c.getDate().isAfter(signalDate))
                .toList();
        double weeklyOpen = currentWeek.isEmpty() ? breakoutCandle.getOpen() : currentWeek.get(0).getOpen();
        double weeklyHigh = currentWeek.stream().mapToDouble(UsCandle::getHigh).max().orElse(breakoutCandle.getHigh());
        double weeklyLow = currentWeek.stream().mapToDouble(UsCandle::getLow).min().orElse(breakoutCandle.getLow());
        double weeklyRange = weeklyHigh - weeklyLow;
        double bodyPct = weeklyRange > 0
                ? Math.abs(breakoutCandle.getClose() - weeklyOpen) / weeklyRange * 100.0 : 0;
        double weeklyGainPct = weeklyOpen > 0
                ? (breakoutCandle.getClose() - weeklyOpen) / weeklyOpen * 100.0 : 0;

        List<String> labels = new ArrayList<>();
        labels.add(!hasTenWeeklyCandles
                ? String.format("🗂️ Weekly History Unavailable (%d/10)", lastTenWeekly.size())
                : momentumPass ? "⚡ Momentum Pass" : "⚠️ Momentum Fail");
        labels.add(bodyPct >= 60.0 ? "💪 Strong Body" : "📉 Weak Body");
        if (weeklyGainPct > 10.0) labels.add("🚀 Big Mover");
        return new BreakoutClassification(String.join(" | ", labels), bodyPct);
    }

    private record BreakoutClassification(String labels, double bodyPct) {}

    private String buildSellAlert(UsWeeklyBreakoutState state, UsCandle candle) {
        double close     = candle.getClose();
        double slLevel   = state.getPrevDailyHigh();
        double riskPct   = ((slLevel - close) / close) * 100.0;
        double movePct   = state.getWeekStartOpen() > 0
                ? ((close - state.getWeekStartOpen()) / state.getWeekStartOpen()) * 100.0 : 0;
        double prevWkChg = state.getPrevWeekClose() > 0
                ? ((close - state.getPrevWeekClose()) / state.getPrevWeekClose()) * 100.0 : 0;

        log.info("[US-WEEKLY] 🔴 SELL {} @ ${} | weeklyL ${} | SL ${} ({}% risk)",
                state.getTicker(), close, state.getWeeklyLow(), slLevel,
                String.format("%.2f", riskPct));

        return String.format(
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
                candle.getDate());
    }}
