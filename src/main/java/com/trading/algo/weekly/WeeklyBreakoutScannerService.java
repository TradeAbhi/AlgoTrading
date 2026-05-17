package com.trading.algo.weekly;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.trading.algo.config.OrbConfig;
import com.trading.algo.telegram.TelegramService;

import jakarta.annotation.PostConstruct;

/**
 * Weekly Breakout Scanner — Nifty 500
 *
 * Strategy:
 *   Every Monday 9:31 AM  → seed previous week's high/low for all symbols.
 *   Mon–Fri    3:31 PM    → fetch today's completed daily candle.
 *                           BUY  if daily close > weeklyHigh (with volume confirmation).
 *                           SELL if daily close < weeklyLow  (with volume confirmation).
 *
 * Rolling level rules (same as ORB):
 *   weeklyHigh only moves UP   — pierce-no-close raises the bar.
 *   weeklyLow  only moves DOWN — pierce-no-close drops the bar.
 *   Opposite side level FROZEN until signal triggers,
 *   then shifts to the previous daily candle's low (BUY) or high (SELL).
 *
 * Filters:
 *   1. Weekly range width ≥ 1.5% and ≤ 8%
 *   2. Breakout daily candle volume ≥ 1.5× average weekly volume
 *
 * Endpoints (via WeeklyBreakoutController):
 *   GET /weekly/capture  — manual seed trigger
 *   GET /weekly/scan     — manual scan trigger
 *   GET /weekly/state    — view all symbol states
 *   GET /weekly/watching — view unalerted symbols only
 */
@Service
public class WeeklyBreakoutScannerService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyBreakoutScannerService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ── Filters ──────────────────────────────────────────────────────────────
    private static final double MIN_WEEKLY_RANGE_PCT  = 1.5;
    private static final double MAX_WEEKLY_RANGE_PCT  = 8.0;
    private static final double MIN_VOLUME_MULTIPLIER = 1.5;

    // ── Timing ───────────────────────────────────────────────────────────────
    private static final LocalTime SEED_FROM  = LocalTime.of(9, 31);
    private static final LocalTime MARKET_END = LocalTime.of(15, 31);

    private final OrbConfig orbConfig;
    private final WeeklyBreakoutStateStore stateStore;
    private final TelegramService telegramService;
    private final RestTemplate restTemplate;

    public WeeklyBreakoutScannerService(OrbConfig orbConfig,
                                        WeeklyBreakoutStateStore stateStore,
                                        TelegramService telegramService,
                                        RestTemplate restTemplate) {
        this.orbConfig       = orbConfig;
        this.stateStore      = stateStore;
        this.telegramService = telegramService;
        this.restTemplate    = restTemplate;
    }

    // ── Startup fallback ─────────────────────────────────────────────────────
    @PostConstruct
    public void seedOnStartupIfNeeded() {
        LocalTime now     = LocalTime.now();
        LocalDate today   = LocalDate.now();
        DayOfWeek dow     = today.getDayOfWeek();
        boolean isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        boolean inHours   = !now.isBefore(SEED_FROM) && !now.isAfter(MARKET_END);

        if (isWeekday && inHours && stateStore.size() == 0) {
            log.info("[WEEKLY] App started during market hours with empty state — seeding now");
            seedPreviousWeekRange();
        } else {
            log.info("[WEEKLY] Startup check: weekday={} inHours={} storeSize={} — no seed needed",
                isWeekday, inHours, stateStore.size());
        }
    }

    // ── Monday 9:31 AM: seed previous week's range ───────────────────────────
    @Scheduled(cron = "0 31 9 * * MON", zone = "Asia/Kolkata")
    public void seedPreviousWeekRange() {
        log.info("[WEEKLY] Seeding previous week's high/low for Nifty 500...");
        stateStore.clear();

        Map<String, String> keyToSymbol = orbConfig.getKeyToSymbolMap();
        int seeded = 0, skipped = 0;

        for (Map.Entry<String, String> entry : keyToSymbol.entrySet()) {
            String instrumentKey = entry.getKey();
            String symbol        = entry.getValue();

            try {
                // Fetch last 2 weekly candles — index 0 = current week, index 1 = prev week
                List<List<Object>> weeklyCandles = fetchWeeklyCandles(instrumentKey, 2);
                if (weeklyCandles == null || weeklyCandles.size() < 2) {
                    skipped++;
                    continue;
                }

                List<Object> prevWeek = weeklyCandles.get(1);
                // [0]=timestamp [1]=open [2]=high [3]=low [4]=close [5]=volume
                double wOpen   = toDouble(prevWeek.get(1));
                double wHigh   = toDouble(prevWeek.get(2));
                double wLow    = toDouble(prevWeek.get(3));
                double wClose  = toDouble(prevWeek.get(4));
                long   wVolume = toLong(prevWeek.get(5));

                // ── Filter 1: Weekly range width ─────────────────────────────
                double rangeWidth = ((wHigh - wLow) / wLow) * 100.0;
                if (rangeWidth < MIN_WEEKLY_RANGE_PCT || rangeWidth > MAX_WEEKLY_RANGE_PCT) {
                    log.debug("[WEEKLY] {} skipped — range {:.2f}% (must be {}-{}%)",
                        symbol, rangeWidth, MIN_WEEKLY_RANGE_PCT, MAX_WEEKLY_RANGE_PCT);
                    skipped++;
                    continue;
                }

                // Fetch Monday's opening price for % move context in alerts
                double weekStartOpen = fetchTodayOpen(instrumentKey);

                stateStore.put(symbol, WeeklyBreakoutState.builder()
                    .symbol(symbol)
                    .instrumentKey(instrumentKey)
                    .weeklyHigh(wHigh)
                    .weeklyLow(wLow)
                    .weeklyOpen(wOpen)
                    .weeklyVolume(wVolume)
                    .buyAlerted(false)
                    .sellAlerted(false)
                    // prevDaily seeded from prev week high/low
                    // will be updated after each daily scan
                    .prevDailyHigh(wHigh)
                    .prevDailyLow(wLow)
                    .prevWeekClose(wClose)
                    .weekStartOpen(weekStartOpen)
                    .build());

                log.debug("[WEEKLY] Seeded {} H:{} L:{} range:{}%",
                    symbol, wHigh, wLow, String.format("%.2f", rangeWidth));
                seeded++;

            } catch (Exception e) {
                log.error("[WEEKLY] Seed error for {} ({}): {}", symbol, instrumentKey, e.getMessage());
                skipped++;
            }
        }
        log.info("[WEEKLY] Seeded {} symbols ({} skipped). Daily scan starts at 3:31 PM.", seeded, skipped);
    }

    // ── Mon–Fri 3:31 PM: scan completed daily candle ─────────────────────────
    @Scheduled(cron = "0 31 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanDailyClose() {
        if (stateStore.size() == 0) {
            log.warn("[WEEKLY] State store empty at 3:31 PM — was seedPreviousWeekRange skipped?");
            return;
        }

        log.info("[WEEKLY] Daily close scan — {} symbols being watched", stateStore.size());
        int buyFired = 0, sellFired = 0;

        for (WeeklyBreakoutState state : stateStore.all()) {
            if (state.isBuyAlerted() && state.isSellAlerted()) continue;

            String symbol        = state.getSymbol();
            String instrumentKey = state.getInstrumentKey();

            try {
                List<List<Object>> dailyCandles = fetchDailyCandles(instrumentKey, 1);
                if (dailyCandles == null || dailyCandles.isEmpty()) continue;

                List<Object> today = dailyCandles.get(0);
                double dHigh   = toDouble(today.get(2));
                double dLow    = toDouble(today.get(3));
                double dClose  = toDouble(today.get(4));
                long   dVolume = toLong(today.get(5));

                // ── BUY side ─────────────────────────────────────────────────
                // weeklyHigh only moves UP. weeklyLow stays FROZEN.
                if (!state.isBuyAlerted()) {
                    if (dClose > state.getWeeklyHigh()) {
                        // ── Filter 2: Volume confirmation ─────────────────────
                        long avgWeeklyVol = state.getWeeklyVolume() / 5; // approx daily avg
                        if (dVolume >= (long)(avgWeeklyVol * MIN_VOLUME_MULTIPLIER)) {
                            state.setWeeklyLow(state.getPrevDailyLow());
                            sendBuyAlert(state, dClose, dHigh, dLow, dVolume);
                            state.setBuyAlerted(true);
                            buyFired++;
                        } else {
                            log.debug("[WEEKLY] {} BUY skipped — low volume ({} < {}x avg {})",
                                symbol, dVolume, MIN_VOLUME_MULTIPLIER, avgWeeklyVol);
                        }
                    } else if (dHigh > state.getWeeklyHigh()) {
                        // Pierced weekly high intraday but daily close didn't confirm
                        // → raise weeklyHigh (only ever moves up)
                        log.debug("[WEEKLY] {} weeklyHigh {} → {} (daily pierce, no close)",
                            symbol, state.getWeeklyHigh(), dHigh);
                        state.setWeeklyHigh(dHigh);
                    }
                    // dHigh <= weeklyHigh → didn't even reach level, nothing changes
                }

                // ── SELL side ────────────────────────────────────────────────
                // weeklyLow only moves DOWN. weeklyHigh stays FROZEN.
                if (!state.isSellAlerted()) {
                    if (dClose < state.getWeeklyLow()) {
                        long avgWeeklyVol = state.getWeeklyVolume() / 5;
                        if (dVolume >= (long)(avgWeeklyVol * MIN_VOLUME_MULTIPLIER)) {
                            state.setWeeklyHigh(state.getPrevDailyHigh());
                            sendSellAlert(state, dClose, dHigh, dLow, dVolume);
                            state.setSellAlerted(true);
                            sellFired++;
                        } else {
                            log.debug("[WEEKLY] {} SELL skipped — low volume ({} < {}x avg {})",
                                symbol, dVolume, MIN_VOLUME_MULTIPLIER, avgWeeklyVol);
                        }
                    } else if (dLow < state.getWeeklyLow()) {
                        // Pierced weekly low intraday but daily close didn't confirm
                        // → drop weeklyLow (only ever moves down)
                        log.debug("[WEEKLY] {} weeklyLow {} → {} (daily pierce, no close)",
                            symbol, state.getWeeklyLow(), dLow);
                        state.setWeeklyLow(dLow);
                    }
                    // dLow >= weeklyLow → didn't reach level, nothing changes
                }

                // Update previous daily candle reference for next scan
                state.setPrevDailyHigh(dHigh);
                state.setPrevDailyLow(dLow);

            } catch (Exception e) {
                log.error("[WEEKLY] Scan error for {} ({}): {}", symbol, instrumentKey, e.getMessage());
            }
        }
        log.info("[WEEKLY] Daily scan done → BUY:{} SELL:{}", buyFired, sellFired);
    }

    // ── Manual triggers ───────────────────────────────────────────────────────
    public void triggerManualSeed() { seedPreviousWeekRange(); }
    public void triggerManualScan() { scanDailyClose(); }

    // ── Telegram alert builders ───────────────────────────────────────────────
    private void sendBuyAlert(WeeklyBreakoutState state, double close,
                               double dHigh, double dLow, long volume) {
        double slLevel    = state.getPrevDailyLow();
        double riskPct    = ((close - slLevel) / close) * 100.0;
        double movePct    = state.getWeekStartOpen() > 0
                            ? ((close - state.getWeekStartOpen()) / state.getWeekStartOpen()) * 100.0 : 0;
        double gapFromPrevWeek = state.getPrevWeekClose() > 0
                            ? ((close - state.getPrevWeekClose()) / state.getPrevWeekClose()) * 100.0 : 0;
        String niftyTrend = getNiftyWeeklyTrend();

        telegramService.sendMessage(String.format(
            "🟢 *WEEKLY BUY BREAKOUT*%n" +
            "📌 *%s*%n" +
            "────────────────%n" +
            "📈 Daily Close:     ₹%.2f%n" +
            "🎯 Above level:     ₹%.2f%n" +
            "🛑 SL:              ₹%.2f  (%.2f%% risk)%n" +
            "📊 Move this week:  %s%.2f%%%n" +
            "📅 Prev week close: ₹%.2f  (%s%.2f%%)%n" +
            "📦 Daily High/Low:  ₹%.2f / ₹%.2f%n" +
            "📊 Volume:          %,d%n" +
            "🏦 Nifty:           %s",
            state.getSymbol(),
            close,
            state.getWeeklyHigh(),
            slLevel, riskPct,
            movePct >= 0 ? "+" : "", movePct,
            state.getPrevWeekClose(), gapFromPrevWeek >= 0 ? "+" : "", gapFromPrevWeek,
            dHigh, dLow,
            volume,
            niftyTrend));

        log.info("[WEEKLY] 🟢 BUY  {} @ ₹{} | weeklyH ₹{} | SL ₹{} ({}% risk)",
            state.getSymbol(), close, state.getWeeklyHigh(), slLevel,
            String.format("%.2f", riskPct));
    }

    private void sendSellAlert(WeeklyBreakoutState state, double close,
                                double dHigh, double dLow, long volume) {
        double slLevel    = state.getPrevDailyHigh();
        double riskPct    = ((slLevel - close) / close) * 100.0;
        double movePct    = state.getWeekStartOpen() > 0
                            ? ((close - state.getWeekStartOpen()) / state.getWeekStartOpen()) * 100.0 : 0;
        double gapFromPrevWeek = state.getPrevWeekClose() > 0
                            ? ((close - state.getPrevWeekClose()) / state.getPrevWeekClose()) * 100.0 : 0;
        String niftyTrend = getNiftyWeeklyTrend();

        telegramService.sendMessage(String.format(
            "🔴 *WEEKLY SELL BREAKDOWN*%n" +
            "📌 *%s*%n" +
            "────────────────%n" +
            "📉 Daily Close:     ₹%.2f%n" +
            "🎯 Below level:     ₹%.2f%n" +
            "🛑 SL:              ₹%.2f  (%.2f%% risk)%n" +
            "📊 Move this week:  %s%.2f%%%n" +
            "📅 Prev week close: ₹%.2f  (%s%.2f%%)%n" +
            "📦 Daily High/Low:  ₹%.2f / ₹%.2f%n" +
            "📊 Volume:          %,d%n" +
            "🏦 Nifty:           %s",
            state.getSymbol(),
            close,
            state.getWeeklyLow(),
            slLevel, riskPct,
            movePct >= 0 ? "+" : "", movePct,
            state.getPrevWeekClose(), gapFromPrevWeek >= 0 ? "+" : "", gapFromPrevWeek,
            dHigh, dLow,
            volume,
            niftyTrend));

        log.info("[WEEKLY] 🔴 SELL {} @ ₹{} | weeklyL ₹{} | SL ₹{} ({}% risk)",
            state.getSymbol(), close, state.getWeeklyLow(), slLevel,
            String.format("%.2f", riskPct));
    }

    // ── Upstox data fetchers ──────────────────────────────────────────────────

    /**
     * Fetches N weekly candles ending today.
     * Returns newest first (index 0 = current week, index 1 = prev week).
     */
    @SuppressWarnings("unchecked")
    private List<List<Object>> fetchWeeklyCandles(String instrumentKey, int weeks) {
        try {
            String toDate   = LocalDate.now().format(DATE_FMT);
            String fromDate = LocalDate.now().minusWeeks(weeks + 1).format(DATE_FMT);
            String encoded  = instrumentKey.replace("|", "%7C");

            java.net.URI uri = java.net.URI.create(String.format(
                "https://api.upstox.com/v3/historical-candle/%s/weeks/1/%s/%s",
                encoded, toDate, fromDate));

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return null;

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) return null;

            return (List<List<Object>>) data.get("candles");

        } catch (Exception e) {
            log.error("[WEEKLY] fetchWeeklyCandles failed for {}: {}", instrumentKey, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches N most recent completed daily candles (intraday daily endpoint).
     * Returns newest first.
     */
    @SuppressWarnings("unchecked")
    private List<List<Object>> fetchDailyCandles(String instrumentKey, int days) {
        try {
            String encoded = instrumentKey.replace("|", "%7C");

            java.net.URI uri = java.net.URI.create(
                "https://api.upstox.com/v3/historical-candle/intraday/" + encoded + "/days/1");

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return null;

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) return null;

            return (List<List<Object>>) data.get("candles");

        } catch (Exception e) {
            log.error("[WEEKLY] fetchDailyCandles failed for {}: {}", instrumentKey, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches today's opening price (first daily candle open).
     * Used to compute % move from week open in alerts.
     */
    private double fetchTodayOpen(String instrumentKey) {
        try {
            List<List<Object>> candles = fetchDailyCandles(instrumentKey, 1);
            if (candles == null || candles.isEmpty()) return 0;
            return toDouble(candles.get(0).get(1)); // open
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Fetches Nifty 50 weekly change for alert context.
     */
    @SuppressWarnings("unchecked")
    private String getNiftyWeeklyTrend() {
        try {
            String niftyKey = "NSE_INDEX|Nifty 50".replace("|", "%7C").replace(" ", "%20");

            java.net.URI uri = java.net.URI.create(
                "https://api.upstox.com/v3/historical-candle/intraday/" + niftyKey + "/days/1");

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return "N/A";

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) return "N/A";

            List<List<Object>> candles = (List<List<Object>>) data.get("candles");
            if (candles == null || candles.isEmpty()) return "N/A";

            double nOpen  = toDouble(candles.get(0).get(1));
            double nClose = toDouble(candles.get(0).get(4));
            double chg    = ((nClose - nOpen) / nOpen) * 100.0;

            return String.format("%s %.2f%%  (₹%.0f)",
                chg >= 0 ? "🟢" : "🔴", chg, nClose);

        } catch (Exception e) {
            log.warn("[WEEKLY] getNiftyWeeklyTrend failed: {}", e.getMessage());
            return "N/A";
        }
    }

    // ── Type helpers ──────────────────────────────────────────────────────────
    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private long toLong(Object val) {
        return val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString());
    }
}