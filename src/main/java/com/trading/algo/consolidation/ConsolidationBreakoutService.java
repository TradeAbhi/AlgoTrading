package com.trading.algo.consolidation;

import com.trading.algo.dtos.Candle;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consolidation Breakout Scanner for Nifty 50 and Bank Nifty.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DATA SOURCE — INDEX CANDLES (NOT FUTURES)
 * ─────────────────────────────────────────────────────────────────────────────
 * Currently using NSE index candles (NSE_INDEX|Nifty 50, NSE_INDEX|Nifty Bank).
 *
 * WHY INDEX AND NOT FUTURES?
 *   Index candles are continuous — no expiry rollover gaps, no premium/discount
 *   distortion. Price action on the index mirrors futures closely enough for
 *   consolidation zone detection.
 *
 * IMPORTANT — VOLUME ON INDEX CANDLES IS NOT RELIABLE:
 *   NSE index candles carry synthetic/aggregated volume that does NOT represent
 *   actual futures or options traded volume. It is the sum of constituent stock
 *   volumes weighted by index methodology — NOT the Nifty futures contract volume.
 *   Therefore the volume confirmation condition is COMMENTED OUT below.
 *
 * TO SWITCH TO FUTURES (when you want real volume):
 *   Replace instrument keys with the active monthly futures contract, e.g.:
 *     NSE_FO|NIFTY25JUNFUT
 *     NSE_FO|BANKNIFTY25JUNFUT
 *   Then uncomment the volume check — futures volume is real and meaningful.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CONSOLIDATION DETECTION LOGIC
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Take the last N closed candles as the "consolidation window":
 *      15-min: N = 4 candles  → 1 hour of price coiling
 *       5-min: N = 6 candles  → 30 minutes of price coiling
 *
 * 2. Measure the zone:
 *      zoneHigh  = max(high)  of those N candles
 *      zoneLow   = min(low)   of those N candles
 *      midpoint  = (zoneHigh + zoneLow) / 2
 *      rangeWidth = (zoneHigh - zoneLow) / midpoint × 100  [as %]
 *
 * 3. Consolidation is valid only if rangeWidth ≤ maxRangePct threshold.
 *
 * 4. Breakout confirmed when the CURRENT (latest closed) candle:
 *      BULLISH: close > zoneHigh
 *      BEARISH: close < zoneLow
 *
 * 5. [COMMENTED OUT] Volume confirmation: current volume ≥ 1.5× avg of window.
 *    Not applicable for index candles — re-enable when using futures.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THESE RANGE THRESHOLDS?
 * ─────────────────────────────────────────────────────────────────────────────
 * Nifty 50 typical single 15-min candle range  ≈ 0.10% – 0.20% of price
 * Bank Nifty typical single 15-min candle range ≈ 0.15% – 0.30% of price
 *
 * 15-min threshold = 0.40%:
 *   4 candles × ~0.10% avg range each = 0.40% total box.
 *   This means each candle is genuinely compressed — not just normal movement.
 *   If set lower (e.g. 0.20%) → almost never fires.
 *   If set higher (e.g. 0.80%) → fires on every sideways hour, too noisy.
 *   Suggested tuning range: 0.25% – 0.60% depending on market volatility.
 *
 * 5-min threshold = 0.30%:
 *   5-min candles are individually smaller than 15-min candles.
 *   6 candles × ~0.05% avg range each = 0.30% total box.
 *   Tighter than 15-min because the window covers less time (30 min vs 1 hour).
 *   Suggested tuning range: 0.20% – 0.45%.
 *
 * NOTE: These are starting-point estimates, NOT backtested values.
 *   Tune based on live observation:
 *   - Too many false signals → lower the threshold
 *   - Almost never firing   → raise the threshold
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SCHEDULE
 * ─────────────────────────────────────────────────────────────────────────────
 *  5-min scan : every 5 minutes  from 9:20 AM to 3:25 PM
 * 15-min scan : every 15 minutes from 9:31 AM to 3:16 PM
 * Dedup reset : midnight daily
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsolidationBreakoutService {

    // ── Consolidation window sizes ────────────────────────────────────────────

    // 15-min: 4 candles = 1 hour of consolidation
    private static final int LOOKBACK_15M = 4;

    // 5-min: 6 candles = 30 minutes of consolidation
    private static final int LOOKBACK_5M = 6;

    // ── Range thresholds — see class-level Javadoc for full reasoning ─────────

    // 15-min: zone high-to-low must be ≤ 0.40% of midpoint price.
    // Based on: Nifty avg 15-min candle range ~0.10-0.20%, so 4 candles in 0.40% = genuinely tight.
    // Tune down to 0.25% for stricter signals, up to 0.60% for more signals.
    private static final double MAX_RANGE_PCT_15M = 0.40;

    // 5-min: zone high-to-low must be ≤ 0.30% of midpoint price.
    // Based on: 5-min candles are smaller; 6 candles in 0.30% = compressed 30-min range.
    // Tune down to 0.20% for stricter signals, up to 0.45% for more signals.
    private static final double MAX_RANGE_PCT_5M = 0.30;

    // ── Volume confirmation — COMMENTED OUT (index volume is not real) ────────
    //
    // When using NSE_INDEX candles, the volume field is synthetic (sum of constituent
    // stock volumes) and does NOT represent Nifty/BankNifty futures traded volume.
    // Applying a volume filter on index candles would produce meaningless results.
    //
    // TO RE-ENABLE: switch instrument keys to futures (NSE_FO|NIFTY25JUNFUT etc.)
    // and uncomment the block marked [VOLUME CHECK] in the evaluate() method.
    //
    // private static final double MIN_VOLUME_RATIO = 1.5;  // volume >= 1.5x avg of window

    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // ── Instruments — index candles (see class Javadoc for futures alternative) ─

    private static final Map<String, String> INDICES = Map.of(
            "NIFTY 50",   "NSE_INDEX|Nifty 50",
            "BANK NIFTY", "NSE_INDEX|Nifty Bank"
    );

    // ── Close distance threshold filter ─────────────────────────────────────────
    // Minimum distance from previous day close required to take a trade.
    // This prevents trades when price is too close to PDC (price magnet effect).
    private static final double CLOSE_DISTANCE_THRESHOLD_NIFTY = 40.0;
    private static final double CLOSE_DISTANCE_THRESHOLD_BANKNIFTY = 70.0;

    private final UpstoxHistoricalCandleService candleService;
    private final com.trading.algo.upstox.UpstoxTokenService upstoxTokenService;
    private final TelegramService               telegramService;

    // Dedup: key = "LABEL:TF:zoneHigh:zoneLow" — one alert per zone per day
    private final Map<String, Boolean> alertedZones = new ConcurrentHashMap<>();

    // ── Scheduled scans ───────────────────────────────────────────────────────

    @Scheduled(cron = "0 20/5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void scan5mMorning() { runScan(false); }

    @Scheduled(cron = "0 0/5 10-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scan5mIntraday() {
        if (LocalTime.now().isAfter(MARKET_CLOSE)) return;
        runScan(false);
    }

    @Scheduled(cron = "0 31/15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void scan15mMorning() { runScan(true); }

    @Scheduled(cron = "0 1/15 10-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scan15mIntraday() {
        if (LocalTime.now().isAfter(MARKET_CLOSE)) return;
        runScan(true);
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
    public void resetDailyAlerts() {
        alertedZones.clear();
        log.info("Consolidation breakout dedup store reset for new day");
    }

    public void runManualScan() {
        runScan(false);
        runScan(true);
    }

    // ── Core scan ─────────────────────────────────────────────────────────────

    private void runScan(boolean is15m) {
        // Check if Upstox is authenticated before proceeding
        if (!upstoxTokenService.isAuthenticated()) {
            log.debug("Consolidation scan skipped - Upstox not authenticated");
            return;
        }

        LocalDate today = LocalDate.now();
        String tf = is15m ? "15m" : "5m";
        log.info("Consolidation scan [{}] at {}", tf, LocalTime.now().format(TIME_FMT));

        INDICES.forEach((label, instrumentKey) -> {
            try {
                List<Candle> candles = is15m
                        ? candleService.fetchDayCandles(instrumentKey, today)
                        : candleService.fetch5mDayCandles(instrumentKey, today);

                if (candles == null || candles.isEmpty()) {
                    log.debug("[{}][{}] No candles returned", tf, label);
                    return;
                }

                // Fetch previous day close for PDC filter
                List<Candle> prevCandles = candleService.fetchDayCandles(instrumentKey, today.minusDays(
                        today.getDayOfWeek().getValue() == 1 ? 3 : // Monday -> Friday
                        today.getDayOfWeek().getValue() == 7 ? 2 : 1)); // Sunday -> Friday, else -1 day
                double prevDayClose = (prevCandles != null && !prevCandles.isEmpty())
                        ? prevCandles.get(prevCandles.size() - 1).getClose() : 0.0;

                evaluate(label, candles, is15m, prevDayClose);

            } catch (Exception e) {
                log.error("[{}][{}] Scan error: {}", tf, label, e.getMessage());
            }
        });
    }

    // ── Consolidation detection + breakout check ──────────────────────────────

    private void evaluate(String label, List<Candle> candles, boolean is15m, double prevDayClose) {
        int    lookback    = is15m ? LOOKBACK_15M      : LOOKBACK_5M;
        double maxRangePct = is15m ? MAX_RANGE_PCT_15M : MAX_RANGE_PCT_5M;
        String tf          = is15m ? "15m"             : "5m";

        if (candles.size() < lookback + 1) {
            log.debug("[{}][{}] Not enough candles: {}", tf, label, candles.size());
            return;
        }

        Candle       current = candles.get(candles.size() - 1);
        List<Candle> window  = candles.subList(candles.size() - 1 - lookback, candles.size() - 1);

        // ── Step 1: Measure consolidation zone ───────────────────────────────
        double zoneHigh  = window.stream().mapToDouble(Candle::getHigh).max().orElse(0);
        double zoneLow   = window.stream().mapToDouble(Candle::getLow).min().orElse(0);
        double midpoint  = (zoneHigh + zoneLow) / 2.0;
        if (midpoint == 0) return;

        double rangeWidth = (zoneHigh - zoneLow) / midpoint * 100.0;

        if (rangeWidth > maxRangePct) {
            log.debug("[{}][{}] Range {:.3f}% > max {:.2f}% — not consolidating", tf, label, rangeWidth, maxRangePct);
            return;
        }

        // ── Step 2: [VOLUME CHECK — COMMENTED OUT: index volume is not real] ──
        //
        // Index candles (NSE_INDEX|Nifty 50 etc.) carry synthetic volume that is
        // the aggregated volume of all 50 constituent stocks — NOT the Nifty futures
        // contract volume. This number is not meaningful for breakout confirmation.
        //
        // Uncomment this block ONLY when instrument keys are switched to futures:
        //   e.g. NSE_FO|NIFTY25JUNFUT, NSE_FO|BANKNIFTY25JUNFUT
        //
        // double avgVolume   = window.stream().mapToLong(Candle::getVolume).average().orElse(0);
        // double volumeRatio = avgVolume > 0 ? current.getVolume() / avgVolume : 0;
        // if (volumeRatio < MIN_VOLUME_RATIO) {
        //     log.debug("[{}][{}] Volume ratio {:.2f}x < {:.1f}x — skipped", tf, label, volumeRatio, MIN_VOLUME_RATIO);
        //     return;
        // }

        // ── Step 3: Check breakout direction ─────────────────────────────────
        boolean bullishBreakout = current.getClose() > zoneHigh;
        boolean bearishBreakout = current.getClose() < zoneLow;

        if (!bullishBreakout && !bearishBreakout) {
            log.debug("[{}][{}] Close {:.2f} still inside zone [{:.2f} – {:.2f}]",
                    tf, label, current.getClose(), zoneLow, zoneHigh);
            return;
        }

        // ── Step 4: Deduplication — one alert per zone per day ────────────────
        String zoneKey = String.format("%s:%s:%.2f:%.2f", label, tf, zoneHigh, zoneLow);
        if (alertedZones.containsKey(zoneKey)) {
            log.debug("[{}][{}] Already alerted zone [{:.2f}–{:.2f}]", tf, label, zoneLow, zoneHigh);
            return;
        }
        alertedZones.put(zoneKey, Boolean.TRUE);

        // ── Step 5: Consecutive same-direction filter ─────────────────────────
        // If the previous candle (one step back) already triggered a breakout in
        // the same direction, this is the same move continuing — skip it.
        // Works for both 15m (lookback=4, needs 6 candles) and 5m (lookback=6, needs 8 candles).
        if (candles.size() >= lookback + 2) {
            Candle       prev       = candles.get(candles.size() - 2);
            List<Candle> prevWindow = candles.subList(candles.size() - 2 - lookback, candles.size() - 2);
            double prevHigh   = prevWindow.stream().mapToDouble(Candle::getHigh).max().orElse(0);
            double prevLow    = prevWindow.stream().mapToDouble(Candle::getLow).min().orElse(0);
            boolean prevBullish = prev.getClose() > prevHigh;
            boolean prevBearish = prev.getClose() < prevLow;

            if ((bullishBreakout && prevBullish) || (bearishBreakout && prevBearish)) {
                log.info("[{}][{}] Skipping consecutive {} breakout — same direction as previous candle",
                        tf, label, bullishBreakout ? "BULLISH" : "BEARISH");
                return;
            }
        }

        // ── Step 6: PDC (Previous Day Close) filter ────────────────────────────
        // PDC acts as a price magnet. If it sits between entry and target,
        // the move is likely to stall there before reaching the target.
        // BULLISH: skip if PDC is between entry and target (PDC will block the rally)
        // BEARISH: skip if PDC is between target and entry (PDC will block the fall)
        double entry  = current.getClose();
        double sl     = bullishBreakout ? zoneLow : zoneHigh;
        double risk   = Math.abs(entry - sl);
        double target = bullishBreakout
                ? entry + risk * 2.0   // default 2R for live scanner check
                : entry - risk * 2.0;

        if (prevDayClose > 0 && risk > 0) {
            boolean pdcInPath = bullishBreakout
                    ? prevDayClose > entry && prevDayClose < target
                    : prevDayClose < entry && prevDayClose > target;

            if (pdcInPath) {
                log.info("[{}][{}] PDC filter: {} breakout skipped — PDC={} is between entry={} and target={}",
                        tf, label, bullishBreakout ? "BULLISH" : "BEARISH", prevDayClose, entry, target);
                return;
            }
        }

        // ── Step 6b: Close distance threshold filter ───────────────────────────
        // Skip trade if current close is too close to previous day close.
        // This prevents trades when price is near PDC (price magnet effect).
        // Nifty: minimum 40 points away from PDC
        // Bank Nifty: minimum 70 points away from PDC
        if (prevDayClose > 0) {
            double closeDistance = Math.abs(entry - prevDayClose);
            double threshold = label.equals("NIFTY 50")
                    ? CLOSE_DISTANCE_THRESHOLD_NIFTY
                    : CLOSE_DISTANCE_THRESHOLD_BANKNIFTY;

            if (closeDistance < threshold) {
                log.info("[{}][{}] Close distance filter: {} breakout skipped — close={} is only {:.2f} pts from PDC={} (threshold={})",
                        tf, label, bullishBreakout ? "BULLISH" : "BEARISH", entry, closeDistance, prevDayClose, threshold);
                return;
            }
        }

        // ── Step 7: Fire alert ────────────────────────────────────────────────
        String direction = bullishBreakout ? "BULLISH" : "BEARISH";

        String message = buildAlertMessage(label, tf, direction, current,
                zoneHigh, zoneLow, rangeWidth, lookback, prevDayClose);

        telegramService.sendMessageToIndex(message);

        log.info("[{}][{}] {} breakout | close={:.2f} zone=[{:.2f}–{:.2f}] range={:.3f}% PDC={}",
                tf, label, direction, current.getClose(), zoneLow, zoneHigh, rangeWidth, prevDayClose);
    }

    // ── Alert message ─────────────────────────────────────────────────────────

    private String buildAlertMessage(String label, String tf, String direction,
            Candle current, double zoneHigh, double zoneLow,
            double rangeWidth, int lookbackCandles, double prevDayClose) {

        String emoji    = direction.equals("BULLISH") ? "🟢" : "🔴";
        String dirEmoji = direction.equals("BULLISH") ? "📈" : "📉";
        String slLabel  = direction.equals("BULLISH") ? "Zone Low (SL)" : "Zone High (SL)";
        double sl       = direction.equals("BULLISH") ? zoneLow : zoneHigh;
        double zoneWidth = zoneHigh - zoneLow;
        int consolidationMins = lookbackCandles * (tf.equals("15m") ? 15 : 5);
        String pdcLine  = prevDayClose > 0
                ? String.format("%n📌 *Prev Day Close:* `%.2f`  _(PDC cleared — path is open)_", prevDayClose)
                : "";

        return String.format("""
                %s *Consolidation Breakout | %s | %s*

                %s Direction: *%s*
                ⏱ Timeframe: `%s`  |  Consolidated: `%d min`

                📦 *Zone:*
                   High : `%.2f`
                   Low  : `%.2f`
                   Width: `%.2f pts`  (`%.3f%%`)

                🕯 *Breakout Candle:*
                   Close : `%.2f`
                   Time  : `%s`

                🛑 *%s:* `%.2f`%s

                ⚠️ _Volume not shown — using index candles (no real futures volume)_

                #%s #consolidation #breakout #%s""",
                emoji, label, direction,
                dirEmoji, direction,
                tf, consolidationMins,
                zoneHigh, zoneLow, zoneWidth, rangeWidth,
                current.getClose(),
                current.getTimestamp().format(TIME_FMT),
                slLabel, sl, pdcLine,
                label.replace(" ", ""), tf
        );
    }
}
