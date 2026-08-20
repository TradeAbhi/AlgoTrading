package com.trading.algo.consolidation;



import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Identifies consolidation zones from daily candles.
 *
 * ALGORITHM (per window length W, W = 3..10 most recent trading days):
 *   1. zoneHigh = max(high) across the W candles.
 *   2. zoneLow  = min(low) across the W candles.
 *   3. resistanceTouches = count of highs within TOUCH_TOLERANCE_PCT of zoneHigh.
 *   4. supportTouches    = count of lows within TOUCH_TOLERANCE_PCT of zoneLow.
 *   5. Window is a VALID consolidation only if resistanceTouches >= MIN_TOUCHES
 *      AND supportTouches >= MIN_TOUCHES.
 *
 * Because zoneHigh/zoneLow are defined as the window's actual max/min, this
 * single rule also does double duty as the outlier check: a lone spike candle
 * (e.g. one high way above the rest) becomes the new max with only 1 touch,
 * so it fails MIN_TOUCHES on its own and the window is rejected — you don't
 * need a separate "is this candle an outlier" check.
 *
 * TUNING NOTE: TOUCH_TOLERANCE_PCT=0.3% is tight for real daily OHLC data.
 * Watch the DEBUG logs (touch counts per window) — if almost nothing ever
 * qualifies across your watchlist, that's the lever to loosen first, not
 * MIN_TOUCHES.
 */
@Slf4j
@Service
public class ConsolidationZoneService {

    private static final double TOUCH_TOLERANCE_PCT = 0.3;
    private static final int MIN_TOUCHES = 2;
    private static final int MIN_WINDOW_DAYS = 3;
    private static final int MAX_WINDOW_DAYS = 10;

    /**
     * @param symbol  for logging only
     * @param candles daily candles sorted ASCENDING by date (oldest first,
     *                most recent last). Needs at least MIN_WINDOW_DAYS candles;
     *                ideally MAX_WINDOW_DAYS (10) or more so all window
     *                lengths can be tested.
     * @return every window length (3-10) that qualifies as a valid
     *         consolidation. Empty list if none qualify. Caller decides
     *         whether to use the longest, shortest, or all of them.
     */
    public List<ConsolidationZone> findValidZones(String symbol, List<DailyCandle> candles) {
        List<ConsolidationZone> validZones = new ArrayList<>();
        int n = candles.size();

        if (n < MIN_WINDOW_DAYS) {
            log.debug("[{}] only {} candles available, need at least {} — skipping", symbol, n, MIN_WINDOW_DAYS);
            return validZones;
        }

        int maxTestableWindow = Math.min(MAX_WINDOW_DAYS, n);

        for (int windowDays = MIN_WINDOW_DAYS; windowDays <= maxTestableWindow; windowDays++) {
            List<DailyCandle> window = candles.subList(n - windowDays, n);
            evaluateWindow(symbol, window, windowDays).ifPresent(validZones::add);
        }

        if (validZones.isEmpty()) {
            log.debug("[{}] no valid consolidation window found in range {}-{}d", symbol, MIN_WINDOW_DAYS, maxTestableWindow);
        } else {
            log.info("[{}] {} valid consolidation window(s): {}", symbol, validZones.size(),
                    validZones.stream().map(z -> z.windowDays() + "d").toList());
        }

        return validZones;
    }

    private java.util.Optional<ConsolidationZone> evaluateWindow(String symbol, List<DailyCandle> window, int windowDays) {
        BigDecimal zoneHigh = window.stream().map(DailyCandle::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal zoneLow = window.stream().map(DailyCandle::low).min(BigDecimal::compareTo).orElseThrow();

        List<BigDecimal> highs = window.stream().map(DailyCandle::high).toList();
        List<BigDecimal> lows = window.stream().map(DailyCandle::low).toList();

        int resistanceTouches = countTouches(highs, zoneHigh);
        int supportTouches = countTouches(lows, zoneLow);

        log.debug("[{}] window={}d zoneHigh={} (touches={}) zoneLow={} (touches={})",
                symbol, windowDays, zoneHigh, resistanceTouches, zoneLow, supportTouches);

        if (resistanceTouches < MIN_TOUCHES) {
            log.debug("[{}] window={}d REJECTED: resistance touches {} < {}", symbol, windowDays, resistanceTouches, MIN_TOUCHES);
            return java.util.Optional.empty();
        }
        if (supportTouches < MIN_TOUCHES) {
            log.debug("[{}] window={}d REJECTED: support touches {} < {}", symbol, windowDays, supportTouches, MIN_TOUCHES);
            return java.util.Optional.empty();
        }
        if (zoneHigh.compareTo(zoneLow) <= 0) {
            return java.util.Optional.empty();
        }

        BigDecimal widthPct = zoneHigh.subtract(zoneLow)
                .divide(zoneLow, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        log.debug("[{}] window={}d ACCEPTED: zone=[{} - {}] width={}%", symbol, windowDays, zoneLow, zoneHigh, widthPct);

        return java.util.Optional.of(new ConsolidationZone(
                windowDays,
                window.get(0).date(),
                window.get(window.size() - 1).date(),
                zoneHigh,
                zoneLow,
                resistanceTouches,
                supportTouches,
                widthPct
        ));
    }

    /** Counts how many values fall within TOUCH_TOLERANCE_PCT of the given level. */
    private int countTouches(List<BigDecimal> values, BigDecimal level) {
        BigDecimal toleranceAbs = level.multiply(BigDecimal.valueOf(TOUCH_TOLERANCE_PCT / 100.0));
        BigDecimal lower = level.subtract(toleranceAbs);
        BigDecimal upper = level.add(toleranceAbs);
        return (int) values.stream()
                .filter(v -> v.compareTo(lower) >= 0 && v.compareTo(upper) <= 0)
                .count();
    }
}
