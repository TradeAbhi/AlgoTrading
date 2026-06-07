package com.trading.algo.usmarket;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * US Weekly Breakout Scanner — ATH/52-week high stocks only
 *
 * Ticker source (in priority order):
 *   1. In-memory list — populated via POST /us-weekly/upload-tickers
 *      Paste tickers directly from WSJ 52-week high page, no file needed.
 *   2. sp500.csv on classpath (src/main/resources/sp500.csv)
 *      Updated manually each Friday after reviewing WSJ.
 *
 * CSV format (no header required):
 *   NVDA
 *   AAPL
 *   META
 *   (one ticker per line, # lines are comments)
 *
 * Or with optional extra columns (only first column used):
 *   NVDA,NASDAQ,1250.50
 *   AAPL,NYSE,215.30
 *
 * Workflow:
 *   Friday  → check https://www.wsj.com/market-data/stocks/newfiftytwoweekhighsandlows
 *           → copy NYSE + NASDAQ 52-week high tickers
 *           → POST /us-weekly/upload-tickers   (easiest — paste directly)
 *             OR update sp500.csv and restart
 *
 *   Monday 6:00 AM IST → seedPreviousWeekRange() auto-runs
 *   Mon–Fri 2:00 AM IST → scanDailyClose() after US market close (1:30 AM IST)
 */
@Service
public class UsWeeklyBreakoutScannerService {

    private static final Logger log = LoggerFactory.getLogger(UsWeeklyBreakoutScannerService.class);
    private static final String DEFAULT_TICKER_CSV = "sp500.csv";

    private static final double MIN_RANGE_PCT         = 1.5;
    private static final double MAX_RANGE_PCT         = 8.0;
    private static final double MIN_VOLUME_MULTIPLIER = 1.5;

    // In-memory ticker list — set via POST /us-weekly/upload-tickers
    // CopyOnWriteArrayList for thread safety (web thread writes, scheduler reads)
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

    // ── Startup fallback ─────────────────────────────────────────────────────
    @PostConstruct
    public void seedOnStartupIfNeeded() {
        boolean skipStartupSeed = true;

        if (!skipStartupSeed) {
            log.info("[US-WEEKLY] Startup during weekday with empty store — seeding now");
            seedPreviousWeekRange();
        } else {
            log.info("[US-WEEKLY] Startup: weekday={} storeSize={} — no seed needed",
                    true, stateStore.size());
        }
    }

    // ── Upload tickers from controller ───────────────────────────────────────
    /**
     * Called by POST /us-weekly/upload-tickers.
     * Replaces the in-memory ticker list and immediately seeds the store.
     * This is the primary workflow — paste tickers from WSJ, seed runs instantly.
     */
    public void uploadAndSeed(List<String> tickers) {
        uploadedTickers.clear();
        // Normalise — uppercase, strip blanks, skip comments
        for (String t : tickers) {
            String cleaned = t.trim().toUpperCase();
            if (!cleaned.isEmpty() && !cleaned.startsWith("#")) {
                // Handle "NVDA,NASDAQ,1250.50" format — take first column only
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
            log.warn("[US-WEEKLY] No tickers to seed. " +
                    "Use POST /us-weekly/upload-tickers or add {} to resources/", DEFAULT_TICKER_CSV);
            return;
        }
        log.info("[US-WEEKLY] Seeding {} tickers from {}", tickers.size(), tickerSource());

        int seeded = 0, skipped = 0;

        for (String ticker : tickers) {
            try {
                // Fetch last 3 weekly candles:
                //   index size-1 = current incomplete week
                //   index size-2 = last completed week  ← prev week
                //   index size-3 = week before that
                List<UsCandle> weekly = marketDataService.fetchWeeklyCandles(ticker, 3);
                if (weekly.size() < 2) {
                    log.debug("[US-WEEKLY] {} skipped — insufficient weekly data", ticker);
                    skipped++;
                    continue;
                }

                UsCandle prevWeek = weekly.get(weekly.size() - 2);
                double wHigh   = prevWeek.getHigh();
                double wLow    = prevWeek.getLow();
                double wOpen   = prevWeek.getOpen();
                double wClose  = prevWeek.getClose();
                long   wVolume = prevWeek.getVolume();

                // Filter: weekly range 1.5% – 8%
                double rangeWidth = ((wHigh - wLow) / wLow) * 100.0;
                if (rangeWidth < MIN_RANGE_PCT || rangeWidth > MAX_RANGE_PCT) {
                    log.debug("[US-WEEKLY] {} skipped — range {}%",
                            ticker, String.format("%.2f", rangeWidth));
                    skipped++;
                    continue;
                }

                // All tickers from this source are already confirmed ATH/52-week highs
                // (user copied them from WSJ) so is52WeekHigh = true for all
                boolean is52WkHigh = true;

                // Monday opening price for alert context
                List<UsCandle> daily     = marketDataService.fetchDailyCandles(ticker, 5);
                double weekStartOpen     = daily.isEmpty() ? 0 : daily.get(0).getOpen();

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
                        .weekStartOpen(weekStartOpen)
                        .is52WeekHigh(is52WkHigh)
                        .build());

                log.debug("[US-WEEKLY] Seeded {} H:{} L:{} range:{}%",
                        ticker, wHigh, wLow, String.format("%.2f", rangeWidth));
                seeded++;

                Thread.sleep(50);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
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
            log.warn("[US-WEEKLY] State store empty — upload tickers via " +
                    "POST /us-weekly/upload-tickers or wait for Monday seed");
            return;
        }

        log.info("[US-WEEKLY] Daily close scan — {} tickers watched", stateStore.size());
        int buyFired = 0, sellFired = 0;

        for (UsWeeklyBreakoutState state : stateStore.all()) {
            if (state.isBuyAlerted() && state.isSellAlerted()) continue;

            String ticker = state.getTicker();

            try {
                List<UsCandle> daily = marketDataService.fetchDailyCandles(ticker, 2);
                if (daily.isEmpty()) continue;

                UsCandle today = daily.get(daily.size() - 1);
                double dHigh   = today.getHigh();
                double dLow    = today.getLow();
                double dClose  = today.getClose();
                long   dVolume = today.getVolume();
                long   avgDVol = state.getWeeklyVolume() / 5;

                // ── BUY side — weeklyHigh only moves UP ──────────────────────
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
                        log.debug("[US-WEEKLY] {} weeklyHigh {} → {} (pierce no close)",
                                ticker, state.getWeeklyHigh(), dHigh);
                        state.setWeeklyHigh(dHigh);
                    }
                }

                // ── SELL side — weeklyLow only moves DOWN ────────────────────
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
                        log.debug("[US-WEEKLY] {} weeklyLow {} → {} (pierce no close)",
                                ticker, state.getWeeklyLow(), dLow);
                        state.setWeeklyLow(dLow);
                    }
                }

                state.setPrevDailyHigh(dHigh);
                state.setPrevDailyLow(dLow);

                Thread.sleep(50);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[US-WEEKLY] Scan error for {}: {}", ticker, e.getMessage());
            }
        }
        log.info("[US-WEEKLY] Scan done → BUY:{} SELL:{}", buyFired, sellFired);
    }

    // ── Manual triggers ───────────────────────────────────────────────────────
    public void triggerManualSeed() { seedPreviousWeekRange(); }
    public void triggerManualScan() { scanDailyClose(); }

    // ── Ticker resolution — uploaded list takes priority over CSV ────────────
    /**
     * Returns the ticker list to use for seeding.
     * Priority:
     *   1. In-memory uploaded list (set via POST /us-weekly/upload-tickers)
     *   2. sp500.csv from classpath
     */
    private List<String> resolveTickers() {
        if (!uploadedTickers.isEmpty()) {
            return new ArrayList<>(uploadedTickers);
        }
        return loadTickersFromCsv();
    }

    private String tickerSource() {
        return uploadedTickers.isEmpty() ? DEFAULT_TICKER_CSV : "uploaded list";
    }

    /**
     * Reads sp500.csv from classpath.
     * Format: one ticker per line (first comma-separated column used).
     * Lines starting with # are comments.
     */
    private List<String> loadTickersFromCsv() {
        List<String> tickers = new ArrayList<>();
        try {
            var stream = getClass().getClassLoader().getResourceAsStream(DEFAULT_TICKER_CSV);
            if (stream == null) {
                log.warn("[US-WEEKLY] {} not found on classpath. Use POST /us-weekly/upload-tickers instead.",
                        DEFAULT_TICKER_CSV);
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

    // ── Telegram alert builders ───────────────────────────────────────────────
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
    }
}
