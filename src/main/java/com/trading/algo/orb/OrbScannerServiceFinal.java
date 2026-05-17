package com.trading.algo.orb;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.trading.algo.dtos.OrbCandle;
import com.trading.algo.dtos.OrbSymbolState;
import com.trading.algo.telegram.TelegramService;

import jakarta.annotation.PostConstruct;

/**
 * Opening Range Breakout (ORB) Scanner — Nifty 500
 *
 * captureOpeningCandle()
 *   Scheduled at 9:16 AM (not 9:15 — Upstox only returns COMPLETED candles;
 *   fetching at exactly 9:15:00 returns nothing because the candle is still forming).
 *   Also runs via @PostConstruct as a startup fallback: if the app starts
 *   after 9:16 AM on a trading day and the state store is empty, it seeds
 *   immediately so the 9:46+ scan cycles have data to work with.
 *   Uses 15-minute interval — same as scan, no special-casing.
 *
 * scanBreakouts()
 *   Scheduled at 9:46, 10:01, 10:16 … 15:16.
 *   Each run evaluates the just-closed 15-minute candle:
 *     9:46  → 9:30–9:45 candle
 *     10:01 → 9:45–10:00 candle  … and so on.
 *   Uses 15-minute interval — a 15-min close is a much stronger confirmation
 *   than a 1-min close, and the candle high/low covers the full period range.
 */
@Service
public class OrbScannerServiceFinal {

    private static final Logger log = LoggerFactory.getLogger(OrbScannerServiceFinal.class);

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final LocalTime CAPTURE_AT  = LocalTime.of(9, 31);   // 9:15 candle closes at 9:30; safe to fetch at 9:31
    private static final LocalTime SCAN_START  = LocalTime.of(9, 46);
    private static final LocalTime SCAN_END    = LocalTime.of(15, 16);
    private static final LocalTime MARKET_CLOSE= LocalTime.of(15, 15);

    // ── Filters ──────────────────────────────────────────────────────────────
    /** Minimum opening range width as % of candle low. Symbols below this are skipped. */
    private static final double MIN_RANGE_PCT          = 0.5;
    /** Breakout candle volume must be at least this multiple of the opening candle volume. */
    private static final double MIN_VOLUME_MULTIPLIER  = 1.5;

    private final OrbConfig orbConfig;
    private final OrbStateStore stateStore;
    private final TelegramService telegramService;
    private final RestTemplate restTemplate;

    public OrbScannerServiceFinal(OrbConfig orbConfig,
                                  OrbStateStore stateStore,
                                  TelegramService telegramService,
                                  RestTemplate restTemplate) {
        this.orbConfig       = orbConfig;
        this.stateStore      = stateStore;
        this.telegramService = telegramService;
        this.restTemplate    = restTemplate;
    }

    // ── Startup fallback: seed if app missed the 9:16 cron ──────────────────
    @PostConstruct
    public void seedOnStartupIfNeeded() {
        LocalTime now = LocalTime.now();
        LocalDate today = LocalDate.now();
        DayOfWeek dow = today.getDayOfWeek();

        boolean isWeekday   = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        boolean isMarketOpen = !now.isBefore(CAPTURE_AT) && !now.isAfter(MARKET_CLOSE);
        boolean storeEmpty  = stateStore.size() == 0;

        if (isWeekday && isMarketOpen && storeEmpty) {
            log.info("[ORB] App started during market hours with empty state store — seeding now");
            captureOpeningCandle();
        } else {
            log.info("[ORB] Startup check: weekday={} marketOpen={} storeEmpty={} — no seed needed",
                isWeekday, isMarketOpen, storeEmpty);
        }
    }

    // ── 9:31 AM: capture completed 9:15–9:30 opening candle (15-minute) ──────
    @Scheduled(cron = "0 31 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureOpeningCandle() {
        log.info("[ORB] Capturing 9:15 opening candle (15min interval, closed at 9:30)...");
        stateStore.clear();

        Map<String, String> keyToSymbol = orbConfig.getKeyToSymbolMap();

        for (Map.Entry<String, String> entry : keyToSymbol.entrySet()) {
            String instrumentKey = entry.getKey();
            String symbol        = entry.getValue();
            try {
                OrbCandle candle = fetchLatestCandle(symbol, instrumentKey);
                if (candle == null) continue;

                // ── Filter 1: Minimum opening range width ────────────────
                // Range < 0.5% of candle low → too narrow, any tick crosses it.
                // Skip this symbol for today.
                double rangeWidth = ((candle.getHigh() - candle.getLow()) / candle.getLow()) * 100.0;
                if (rangeWidth < MIN_RANGE_PCT) {
                    log.debug("[ORB] {} skipped — opening range too narrow ({:.2f}% < {}%)",
                        symbol, rangeWidth, MIN_RANGE_PCT);
                    continue;
                }

                // Fetch previous day's close for gap % and alert context
                double prevClose = fetchPrevDayClose(symbol, instrumentKey);

                stateStore.put(symbol, OrbSymbolState.builder()
                    .symbol(symbol)
                    .instrumentKey(instrumentKey)
                    .rollingHigh(candle.getHigh())
                    .rollingLow(candle.getLow())
                    .buyAlerted(false)
                    .sellAlerted(false)
                    // prevCandle seeded from 9:15 candle itself — used as the
                    // invalidation reference if the very first scan (9:46) triggers
                    .prevCandleHigh(candle.getHigh())
                    .prevCandleLow(candle.getLow())
                    // store opening candle volume for volume filter in scan
                    .openingCandleVolume(candle.getVolume())
                    // store open price and prev close for alert enrichment
                    .openPrice(candle.getOpen())
                    .prevDayClose(prevClose)
                    .build());

                log.debug("[ORB] Seeded {} H:{} L:{} range:{}%",
                    symbol, candle.getHigh(), candle.getLow(),
                    String.format("%.2f", rangeWidth));

            } catch (Exception e) {
                log.error("[ORB] captureOpeningCandle error for {} ({}): {}",
                    symbol, instrumentKey, e.getMessage());
            }
        }
        log.info("[ORB] Seeded {} symbols. First scan at 9:46.", stateStore.size());
    }

    // ── 9:46, 10:01, 10:16 … 15:16: scan just-closed 15-min candle ──────────
    @Scheduled(cron = "0 1,16,31,46 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanBreakouts() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(SCAN_START) || now.isAfter(SCAN_END)) return;

        if (stateStore.size() == 0) {
            log.warn("[ORB] State store empty at scan time {} — was captureOpeningCandle skipped?", now);
            return;
        }

        log.info("[ORB] Scan at {} (15min candle), watching {} symbols", now, stateStore.size());
        int buyFired = 0, sellFired = 0;

        for (OrbSymbolState state : stateStore.all()) {
            if (state.isBuyAlerted() && state.isSellAlerted()) continue;

            String symbol        = state.getSymbol();
            String instrumentKey = state.getInstrumentKey();

            try {
                OrbCandle candle = fetchLatestCandle(symbol, instrumentKey);
                if (candle == null) continue;

                double close      = candle.getClose();
                double candleHigh = candle.getHigh();
                double candleLow  = candle.getLow();

                // ── BUY side ────────────────────────────────────────────────
                // rollingHigh can only move HIGHER — never lower.
                // rollingLow stays FROZEN at 9:15 candle low throughout BUY watch.
                // It only shifts to prevCandleLow at the moment BUY triggers.
                //
                // Advance rollingHigh only if candle's high exceeds current rollingHigh
                // AND close did not confirm above it (pierce without close = raise the bar).
                // If candle high is lower than rollingHigh — ignore, level stays unchanged.
                if (!state.isBuyAlerted()) {
                    if (close > state.getRollingHigh()) {
                        // ── Filter 2: Volume confirmation ────────────────────
                        // Breakout candle volume must be >= 1.5x opening candle volume.
                        // Low volume breakouts are traps — skip without alerting.
                        if (candle.getVolume() < (long)(state.getOpeningCandleVolume() * MIN_VOLUME_MULTIPLIER)) {
                            log.debug("[ORB] {} BUY skipped — low volume (breakout:{} < {}x opening:{})",
                                symbol, candle.getVolume(), MIN_VOLUME_MULTIPLIER,
                                state.getOpeningCandleVolume());
                        } else {
                            // Confirmed close above rollingHigh with volume → BUY triggered.
                            // Shift rollingLow to previous candle's low (invalidation level).
                            state.setRollingLow(state.getPrevCandleLow());
                            sendBuyAlert(state, candle);
                            state.setBuyAlerted(true);
                            buyFired++;
                        }
                    } else if (candleHigh > state.getRollingHigh()) {
                        // Pierced rollingHigh intrabar but failed to close above →
                        // raise rollingHigh to this candle's high (higher bar to clear).
                        // rollingHigh only ever moves UP. rollingLow stays frozen.
                        log.debug("[ORB] {} rollingHigh {} → {} (pierced no close)",
                            symbol, state.getRollingHigh(), candleHigh);
                        state.setRollingHigh(candleHigh);
                    }
                    // candleHigh <= rollingHigh → price didn't even reach the level, nothing changes
                }

                // ── SELL side ───────────────────────────────────────────────
                // rollingLow can only move LOWER — never higher.
                // rollingHigh stays FROZEN at 9:15 candle high throughout SELL watch.
                // It only shifts to prevCandleHigh at the moment SELL triggers.
                //
                // Advance rollingLow only if candle's low goes below current rollingLow
                // AND close did not confirm below it (pierce without close = drop the bar).
                // If candle low is higher than rollingLow — ignore, level stays unchanged.
                if (!state.isSellAlerted()) {
                    if (close < state.getRollingLow()) {
                        // ── Filter 2: Volume confirmation ────────────────────
                        if (candle.getVolume() < (long)(state.getOpeningCandleVolume() * MIN_VOLUME_MULTIPLIER)) {
                            log.debug("[ORB] {} SELL skipped — low volume (breakout:{} < {}x opening:{})",
                                symbol, candle.getVolume(), MIN_VOLUME_MULTIPLIER,
                                state.getOpeningCandleVolume());
                        } else {
                            // Confirmed close below rollingLow with volume → SELL triggered.
                            // Shift rollingHigh to previous candle's high (invalidation level).
                            state.setRollingHigh(state.getPrevCandleHigh());
                            sendSellAlert(state, candle);
                            state.setSellAlerted(true);
                            sellFired++;
                        }
                    } else if (candleLow < state.getRollingLow()) {
                        // Pierced rollingLow intrabar but failed to close below →
                        // drop rollingLow to this candle's low (lower bar to break).
                        // rollingLow only ever moves DOWN. rollingHigh stays frozen.
                        log.debug("[ORB] {} rollingLow {} → {} (pierced no close)",
                            symbol, state.getRollingLow(), candleLow);
                        state.setRollingLow(candleLow);
                    }
                    // candleLow >= rollingLow → price didn't even reach the level, nothing changes
                }

                // Store this candle's high/low as "previous" for the next scan cycle
                state.setPrevCandleHigh(candleHigh);
                state.setPrevCandleLow(candleLow);

            } catch (Exception e) {
                log.error("[ORB] scanBreakouts error for {} ({}): {}", symbol, instrumentKey, e.getMessage());
            }
        }
        log.info("[ORB] Scan done → BUY:{} SELL:{}", buyFired, sellFired);
    }

    public void triggerManualCapture() { captureOpeningCandle(); }
    public void triggerManualScan()    { scanBreakouts(); }

    // ── Upstox intraday candle fetch ─────────────────────────────────────────
    /**
     * Fetches the most recently completed 15-minute candle for today
     * using the Upstox v3 intraday endpoint.
     *
     * v3 URL: https://api.upstox.com/v3/historical-candle/intraday/{key}/minutes/15
     *
     * Used for BOTH opening candle capture (9:31) and breakout scans (9:46 onwards).
     * Single interval, single endpoint — no special-casing needed.
     * URI.create() prevents RestTemplate encoding '|' as '%7C' (UDAPI100011).
     */
    private OrbCandle fetchLatestCandle(String symbol, String instrumentKey) {
        try {
            // '|' is illegal in a URI path — encode it manually before URI.create().
            // We cannot use URI.create() with raw '|', and we cannot use a String-based
            // getForObject() because RestTemplate double-encodes '%7C' → '%257C'.
            // Manual replace is the only safe approach.
            String encodedKey = instrumentKey.replace("|", "%7C");
            java.net.URI uri = java.net.URI.create(
                "https://api.upstox.com/v3/historical-candle/intraday/"
                + encodedKey + "/minutes/15");

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return null;

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) return null;

            @SuppressWarnings("unchecked")
            List<List<Object>> candles = (List<List<Object>>) data.get("candles");
            if (candles == null || candles.isEmpty()) return null;

            // index 0 = most recently completed candle (Upstox returns newest first)
            // [0]=timestamp [1]=open [2]=high [3]=low [4]=close [5]=volume [6]=oi
            List<Object> c = candles.get(0);
            String rawTs   = c.get(0).toString().substring(0, 19); // strip +05:30

            return OrbCandle.builder()
                .symbol(symbol)
                .instrumentKey(instrumentKey)
                .candleTime(LocalDateTime.parse(rawTs, DT_FMT))
                .open(toDouble(c.get(1)))
                .high(toDouble(c.get(2)))
                .low(toDouble(c.get(3)))
                .close(toDouble(c.get(4)))
                .volume(toLong(c.get(5)))
                .build();

        } catch (Exception e) {
            log.error("[ORB] fetchLatestCandle failed for {} ({}): {}", symbol, instrumentKey, e.getMessage());
            return null;
        }
    }

    // ── Telegram alert builders ──────────────────────────────────────────────
    private void sendBuyAlert(OrbSymbolState state, OrbCandle candle) {
        double close       = candle.getClose();
        double slLevel     = state.getPrevCandleLow();
        double riskPct     = ((close - slLevel) / close) * 100.0;
        double movePct     = ((close - state.getOpenPrice()) / state.getOpenPrice()) * 100.0;
        double gapPct      = state.getPrevDayClose() > 0
                             ? ((state.getOpenPrice() - state.getPrevDayClose()) / state.getPrevDayClose()) * 100.0
                             : 0;
        String gapStr      = state.getPrevDayClose() > 0
                             ? String.format("%s%.2f%%", gapPct >= 0 ? "+" : "", gapPct)
                             : "N/A";
        String niftyTrend  = getNiftyTrend();

        telegramService.sendMessage(String.format(
            "🟢 *ORB BUY BREAKOUT*%n" +
            "📌 *%s*%n" +
            "────────────────%n" +
            "📈 Close:        ₹%.2f%n" +
            "🎯 Above level:  ₹%.2f%n" +
            "🛑 SL:           ₹%.2f  (%.2f%% risk)%n" +
            "📊 Move from open: %s%.2f%%%n" +
            "🌅 Gap from prev:  %s%n" +
            "📅 Prev close:   ₹%.2f%n" +
            "🕐 Candle:       %s%n" +
            "📊 Volume:       %,d%n" +
            "🏦 Nifty:        %s",
            state.getSymbol(),
            close,
            state.getRollingHigh(),
            slLevel, riskPct,
            movePct >= 0 ? "+" : "", movePct,
            gapStr,
            state.getPrevDayClose(),
            candlePeriod(candle.getCandleTime().toLocalTime()),
            candle.getVolume(),
            niftyTrend));
        log.info("[ORB] 🟢 BUY  {} @ ₹{} | SL ₹{} ({}% risk) | gap {} | nifty {}",
            state.getSymbol(), close, slLevel,
            String.format("%.2f", riskPct), gapStr, niftyTrend);
    }

    private void sendSellAlert(OrbSymbolState state, OrbCandle candle) {
        double close       = candle.getClose();
        double slLevel     = state.getPrevCandleHigh();
        double riskPct     = ((slLevel - close) / close) * 100.0;
        double movePct     = ((close - state.getOpenPrice()) / state.getOpenPrice()) * 100.0;
        double gapPct      = state.getPrevDayClose() > 0
                             ? ((state.getOpenPrice() - state.getPrevDayClose()) / state.getPrevDayClose()) * 100.0
                             : 0;
        String gapStr      = state.getPrevDayClose() > 0
                             ? String.format("%s%.2f%%", gapPct >= 0 ? "+" : "", gapPct)
                             : "N/A";
        String niftyTrend  = getNiftyTrend();

        telegramService.sendMessage(String.format(
            "🔴 *ORB SELL BREAKDOWN*%n" +
            "📌 *%s*%n" +
            "────────────────%n" +
            "📉 Close:        ₹%.2f%n" +
            "🎯 Below level:  ₹%.2f%n" +
            "🛑 SL:           ₹%.2f  (%.2f%% risk)%n" +
            "📊 Move from open: %s%.2f%%%n" +
            "🌅 Gap from prev:  %s%n" +
            "📅 Prev close:   ₹%.2f%n" +
            "🕐 Candle:       %s%n" +
            "📊 Volume:       %,d%n" +
            "🏦 Nifty:        %s",
            state.getSymbol(),
            close,
            state.getRollingLow(),
            slLevel, riskPct,
            movePct >= 0 ? "+" : "", movePct,
            gapStr,
            state.getPrevDayClose(),
            candlePeriod(candle.getCandleTime().toLocalTime()),
            candle.getVolume(),
            niftyTrend));
        log.info("[ORB] 🔴 SELL {} @ ₹{} | SL ₹{} ({}% risk) | gap {} | nifty {}",
            state.getSymbol(), close, slLevel,
            String.format("%.2f", riskPct), gapStr, niftyTrend);
    }

    // ── Previous day close fetch ─────────────────────────────────────────────
    /**
     * Fetches the previous trading day's closing price using the v3 historical endpoint.
     * Uses a 2-day window to guarantee we get at least one completed daily candle
     * regardless of weekends or holidays.
     */
    @SuppressWarnings("unchecked")
    private double fetchPrevDayClose(String symbol, String instrumentKey) {
        try {
            String today    = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String fromDate = java.time.LocalDate.now().minusDays(5).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String encodedKey = instrumentKey.replace("|", "%7C");

            java.net.URI uri = java.net.URI.create(String.format(
                "https://api.upstox.com/v3/historical-candle/%s/days/1/%s/%s",
                encodedKey, today, fromDate));

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return 0;

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) return 0;

            List<List<Object>> candles = (List<List<Object>>) data.get("candles");
            if (candles == null || candles.size() < 2) return 0;

            // candles[0] = today (incomplete), candles[1] = previous completed day
            List<Object> prevDay = candles.get(1);
            // [0]=timestamp [1]=open [2]=high [3]=low [4]=close [5]=volume
            return toDouble(prevDay.get(4));

        } catch (Exception e) {
            log.warn("[ORB] fetchPrevDayClose failed for {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    // ── Nifty 50 trend at scan time ───────────────────────────────────────────
    /**
     * Fetches the latest 15-min candle for Nifty 50 index and returns a
     * simple trend string: "🟢 +X.XX%" or "🔴 -X.XX%" relative to prev close.
     * Used purely for alert context — not a filter.
     */
    @SuppressWarnings("unchecked")
    private String getNiftyTrend() {
        try {
            // Nifty 50 index instrument key on Upstox
            String niftyKey = "NSE_INDEX|Nifty 50".replace("|", "%7C").replace(" ", "%20");

            java.net.URI uri = java.net.URI.create(
                "https://api.upstox.com/v3/historical-candle/intraday/" + niftyKey + "/minutes/15");

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return "N/A";

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) return "N/A";

            List<List<Object>> candles = (List<List<Object>>) data.get("candles");
            if (candles == null || candles.isEmpty()) return "N/A";

            // Latest candle close
            double niftyClose = toDouble(candles.get(0).get(4));
            // Open of first candle of the day (last element — Upstox returns newest first)
            double niftyOpen  = toDouble(candles.get(candles.size() - 1).get(1));
            double changePct  = ((niftyClose - niftyOpen) / niftyOpen) * 100.0;

            return String.format("%s %.2f%%  (₹%.0f)",
                changePct >= 0 ? "🟢" : "🔴",
                changePct,
                niftyClose);

        } catch (Exception e) {
            log.warn("[ORB] getNiftyTrend failed: {}", e.getMessage());
            return "N/A";
        }
    }

    // ── Candle period label ──────────────────────────────────────────────────
    /**
     * Converts a 15-minute candle's open timestamp to a "HH:mm–HH:mm" period string.
     * e.g. candle opening at 09:15 → "09:15–09:30"
     *      candle opening at 14:45 → "14:45–15:00"
     */
    private String candlePeriod(java.time.LocalTime candleOpen) {
        java.time.LocalTime candleClose = candleOpen.plusMinutes(15);
        return String.format("%s–%s",
            candleOpen.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            candleClose.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
    }

    // ── Type helpers ─────────────────────────────────────────────────────────
    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private long toLong(Object val) {
        return val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString());
    }
}