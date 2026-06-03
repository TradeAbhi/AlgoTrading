package com.trading.algo.usmarket;


import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.trading.algo.dtos.UsWeeklyBreakoutState;
import com.trading.algo.dtos.UsWeeklyBreakoutStateStore;
import com.trading.algo.entity.UsCandle;
import com.trading.algo.telegram.TelegramService;

import jakarta.annotation.PostConstruct;

/**
 * US Weekly Breakout Scanner (NYSE + NASDAQ)
 *
 * Data sources:
 *   Finviz screener  → 52-week high tickers (seeded once per week)
 *   Yahoo Finance v8 → weekly + daily OHLCV (no API key needed)
 *
 * Schedule (all IST):
 *   Monday 6:00 AM IST  → seedPreviousWeekRange()
 *                          Fetches 52-week high list from Finviz.
 *                          Fetches prev week OHLCV from Yahoo Finance.
 *                          Seeds weeklyHigh / weeklyLow for each ticker.
 *
 *   Mon–Fri 2:00 AM IST → scanDailyClose()
 *                          US market closes at 4:00 PM EST = 1:30 AM IST.
 *                          2:00 AM gives 30 min buffer for data availability.
 *                          Fetches completed daily candle from Yahoo Finance.
 *                          BUY  if daily close > weeklyHigh (with volume).
 *                          SELL if daily close < weeklyLow  (with volume).
 *
 * Rolling level rules (same as Indian weekly strategy):
 *   weeklyHigh only moves UP   — daily candle high > weeklyHigh but close doesn't confirm.
 *   weeklyLow  only moves DOWN — daily candle low  < weeklyLow  but close doesn't confirm.
 *   Opposite side frozen → shifts to prev daily H/L on signal trigger.
 *
 * Filters:
 *   1. Weekly range width: 1.5% ≤ range ≤ 8%
 *   2. Breakout candle volume ≥ 1.5× average daily volume (weeklyVol / 5)
 *   3. 52-week high flag from Finviz (BUY signals only from this list for higher conviction)
 */
@Service
public class UsWeeklyBreakoutScannerService {

    private static final Logger log = LoggerFactory.getLogger(UsWeeklyBreakoutScannerService.class);

    private static final double MIN_RANGE_PCT         = 1.5;
    private static final double MAX_RANGE_PCT         = 8.0;
    private static final double MIN_VOLUME_MULTIPLIER = 1.5;

    private static final LocalTime SEED_VALID_FROM = LocalTime.of(6, 0);
    private static final LocalTime SEED_VALID_TO   = LocalTime.of(23, 59);

    private final UsMarketDataService    marketDataService;
    private final UsWeeklyBreakoutStateStore stateStore;
    private final TelegramService        telegramService;

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
        DayOfWeek dow     = LocalDate.now().getDayOfWeek();
        boolean isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        LocalTime now     = LocalTime.now();

        if (isWeekday && !now.isBefore(SEED_VALID_FROM) && stateStore.size() == 0) {
            log.info("[US-WEEKLY] Startup during weekday with empty store — seeding now");
            seedPreviousWeekRange();
        } else {
            log.info("[US-WEEKLY] Startup check: weekday={} storeSize={} — no seed needed",
                    isWeekday, stateStore.size());
        }
    }

    // ── Monday 6:00 AM IST: seed previous week's range ───────────────────────
    @Scheduled(cron = "0 0 6 * * MON", zone = "Asia/Kolkata")
    public void seedPreviousWeekRange() {
        log.info("[US-WEEKLY] Seeding from Finviz 52-week high list + Yahoo Finance...");
        stateStore.clear();

        // Step 1: Get 52-week high tickers from Finviz
        List<String> tickers52wkHigh = marketDataService.fetch52WeekHighTickers();
        log.info("[US-WEEKLY] Finviz 52-week high list: {} tickers", tickers52wkHigh.size());

        int seeded = 0, skipped = 0;

        for (String ticker : tickers52wkHigh) {
            try {
                // Fetch last 3 weekly candles from Yahoo Finance
                // index 0 = current (incomplete) week
                // index 1 = last completed week (prev week)
                // index 2 = week before that (for prevWeekClose)
                List<UsCandle> weeklyCandles = marketDataService.fetchWeeklyCandles(ticker, 3);
                if (weeklyCandles.size() < 2) {
                    log.debug("[US-WEEKLY] {} skipped — insufficient weekly data", ticker);
                    skipped++;
                    continue;
                }

                // Yahoo returns oldest first → index size-2 = last completed week
                UsCandle prevWeek    = weeklyCandles.get(weeklyCandles.size() - 2);
                UsCandle prevPrevWk  = weeklyCandles.size() >= 3
                        ? weeklyCandles.get(weeklyCandles.size() - 3) : prevWeek;

                double wHigh   = prevWeek.getHigh();
                double wLow    = prevWeek.getLow();
                double wOpen   = prevWeek.getOpen();
                double wClose  = prevWeek.getClose();
                long   wVolume = prevWeek.getVolume();

                // Filter 1: Weekly range width 1.5% – 8%
                double rangeWidth = ((wHigh - wLow) / wLow) * 100.0;
                if (rangeWidth < MIN_RANGE_PCT || rangeWidth > MAX_RANGE_PCT) {
                    log.debug("[US-WEEKLY] {} skipped — range {:.2f}%", ticker, rangeWidth);
                    skipped++;
                    continue;
                }

                // Fetch this week's Monday open for % move context
                List<UsCandle> dailyCandles = marketDataService.fetchDailyCandles(ticker, 5);
                double weekStartOpen = dailyCandles.isEmpty() ? 0 : dailyCandles.get(0).getOpen();

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
                        .is52WeekHigh(true) // all tickers from Finviz are at 52-week highs
                        .build());

                log.debug("[US-WEEKLY] Seeded {} H:{} L:{} range:{}%",
                        ticker, wHigh, wLow, String.format("%.2f", rangeWidth));
                seeded++;

                // Polite delay — avoid hammering Yahoo Finance
                Thread.sleep(200);

            } catch (Exception e) {
                log.error("[US-WEEKLY] Seed error for {}: {}", ticker, e.getMessage());
                skipped++;
            }
        }
        log.info("[US-WEEKLY] Seeded {} tickers ({} skipped). Scan starts at 2:00 AM IST.", seeded, skipped);
    }

    // ── Mon–Fri 2:00 AM IST: scan completed daily candle ─────────────────────
    // US market closes at 4:00 PM EST = 1:30 AM IST next day.
    // 2:00 AM gives 30 min buffer for Yahoo Finance data availability.
    @Scheduled(cron = "0 0 2 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanDailyClose() {
        if (stateStore.size() == 0) {
            log.warn("[US-WEEKLY] State store empty at 2:00 AM — was seedPreviousWeekRange skipped?");
            return;
        }

        log.info("[US-WEEKLY] Daily close scan — {} tickers being watched", stateStore.size());
        int buyFired = 0, sellFired = 0;

        for (UsWeeklyBreakoutState state : stateStore.all()) {
            if (state.isBuyAlerted() && state.isSellAlerted()) continue;

            String ticker = state.getTicker();

            try {
                // Fetch last 2 daily candles — index 0 = yesterday (just closed)
                List<UsCandle> dailyCandles = marketDataService.fetchDailyCandles(ticker, 2);
                if (dailyCandles.isEmpty()) continue;

                UsCandle today  = dailyCandles.get(dailyCandles.size() - 1); // latest completed
                double dHigh    = today.getHigh();
                double dLow     = today.getLow();
                double dClose   = today.getClose();
                long   dVolume  = today.getVolume();
                long   avgDVol  = state.getWeeklyVolume() / 5; // approx daily avg from weekly

                // ── BUY side ──────────────────────────────────────────────────
                // weeklyHigh only moves UP
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

                // ── SELL side ─────────────────────────────────────────────────
                // weeklyLow only moves DOWN
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

                // Update previous daily candle
                state.setPrevDailyHigh(dHigh);
                state.setPrevDailyLow(dLow);

                Thread.sleep(100); // polite delay

            } catch (Exception e) {
                log.error("[US-WEEKLY] Scan error for {}: {}", ticker, e.getMessage());
            }
        }
        log.info("[US-WEEKLY] Scan done → BUY:{} SELL:{}", buyFired, sellFired);
    }

    // ── Manual triggers ───────────────────────────────────────────────────────
    public void triggerManualSeed() { seedPreviousWeekRange(); }
    public void triggerManualScan() { scanDailyClose(); }

    // ── Telegram alert builders ───────────────────────────────────────────────
    private void sendBuyAlert(UsWeeklyBreakoutState state, UsCandle candle) {
        double close       = candle.getClose();
        double slLevel     = state.getPrevDailyLow();
        double riskPct     = ((close - slLevel) / close) * 100.0;
        double movePct     = state.getWeekStartOpen() > 0
                ? ((close - state.getWeekStartOpen()) / state.getWeekStartOpen()) * 100.0 : 0;
        double prevWkChg   = state.getPrevWeekClose() > 0
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
                        "🏆 52-Week High:     %s%n" +
                        "📅 Date (EST):       %s",
                state.getTicker(),
                close,
                state.getWeeklyHigh(),
                slLevel, riskPct,
                movePct >= 0 ? "+" : "", movePct,
                state.getPrevWeekClose(), prevWkChg >= 0 ? "+" : "", prevWkChg,
                candle.getHigh(), candle.getLow(),
                candle.getVolume(),
                state.is52WeekHigh() ? "✅ Yes" : "❌ No",
                candle.getDate()));

        log.info("[US-WEEKLY] 🟢 BUY  {} @ ${} | weeklyH ${} | SL ${} ({}% risk)",
                state.getTicker(), close, state.getWeeklyHigh(), slLevel,
                String.format("%.2f", riskPct));
    }

    private void sendSellAlert(UsWeeklyBreakoutState state, UsCandle candle) {
        double close       = candle.getClose();
        double slLevel     = state.getPrevDailyHigh();
        double riskPct     = ((slLevel - close) / close) * 100.0;
        double movePct     = state.getWeekStartOpen() > 0
                ? ((close - state.getWeekStartOpen()) / state.getWeekStartOpen()) * 100.0 : 0;
        double prevWkChg   = state.getPrevWeekClose() > 0
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
                close,
                state.getWeeklyLow(),
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