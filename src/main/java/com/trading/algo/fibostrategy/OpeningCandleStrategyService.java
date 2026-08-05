package com.trading.algo.fibostrategy;


import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.BacktestTrade.Direction;
import com.trading.algo.entity.BacktestTrade.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opening Candle Strategy:
 *
 * STEP 1 — C1 (9:15) qualification:
 *   wick ratio >= minWickRatio | body >= minCandleBodyPoints | body >= minC1BodyPct% of open
 *   range >= minC1AtrRatio × 20-day ATR | volume >= minC1VolumeMultiplier × avg C1 volume
 *
 * STEP 2 — C2 (9:30) confirmation:
 *   [NEW] C2 volume < C1 volume (genuine consolidation, not distribution)
 *   BUY : C2.low > 50% of C1 | within 1.414 ext | 38.2% rejection if touched
 *   SELL: C2.high < 50% of C1 | within 1.414 ext | 38.2% rejection if touched
 *
 * STEP 3 — Entry at C2 close | SL = C2 low/high ± margin
 *
 * STEP 4 — Simulate:
 *   [NEW] Partial exit at partialExitRR (default 1.5R) — book partialExitQtyPct (default 50%)
 *   [NEW] Trail SL to breakeven after partial exit
 *   Full target at targetRR (default 2.5R)
 *   EOD exit at 3:15 PM
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpeningCandleStrategyService {

    /** Tracks per-run rejection counts across all threads. */
    public static class RejectionTracker {
        private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        public void increment(String reason) {
            counts.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
        }

        public java.util.Map<String, Integer> snapshot() {
            java.util.Map<String, Integer> map = new java.util.LinkedHashMap<>();
            counts.forEach((k, v) -> map.put(k, v.get()));
            return map;
        }
    }

    public static final LocalTime C1_TIME  = LocalTime.of(9, 15);
    public static final LocalTime C2_TIME  = LocalTime.of(9, 30);
    private static final LocalTime EOD_TIME = LocalTime.of(15, 15);

    private final BacktestConfig config;

    /** Backward-compatible overload — uses full fixedRiskRupees, no external filters */
    public Optional<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles) {
        return evaluate(symbol, date, candles, -1.0, 0.0, 0, config.getFixedRiskRupees(), null, null, null, null, 0.0, 0.0, 0.0, 0.0, C2_TIME, null);
    }

    /** Backward-compatible overload with prevDayOpen */
    public Optional<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles,
                                            double adRatio, double dailyAtr, long avgC1Volume,
                                            double riskRupees, Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose,
                                            double niftyVwap, double niftyPrice, double vix, double atr5m) {
        return evaluate(symbol, date, candles, adRatio, dailyAtr, avgC1Volume, riskRupees, prevDayOpen, prevDayHigh, prevDayLow, prevDayClose, niftyVwap, niftyPrice, vix, atr5m, C2_TIME, null);
    }

    /**
     * @param adRatio     A/D ratio (-1 = unavailable, skip filter)
     * @param dailyAtr    20-day ATR in points (0 = skip filter)
     * @param avgC1Volume 5-day avg 9:15 candle volume (0 = skip filter)
     * @param riskRupees  risk in rupees — may be reduced after consecutive losses (Improvement 10)
     * @param prevDayOpen previous day's open price (null = unavailable)
     * @param prevDayHigh previous day's high price (null = unavailable)
     * @param prevDayLow  previous day's low price (null = unavailable)
     * @param prevDayClose previous day's close price (null = unavailable)
     * @param niftyVwap   Nifty VWAP for market trend filter (0 = unavailable)
     * @param niftyPrice  Current Nifty price for market trend filter (0 = unavailable)
     * @param vix         India VIX for market trend filter (0 = unavailable)
     * @param atr5m       5-minute ATR for exhausted moves filter (0 = skip filter)
     * @param c2Time      C2 candle time (default C2_TIME)
     * @param tracker     RejectionTracker for tracking rejection reasons
     */
    public Optional<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles,
                                            double adRatio, double dailyAtr, long avgC1Volume,
                                            double riskRupees, Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose,
                                            double niftyVwap, double niftyPrice, double vix, double atr5m,
                                            LocalTime c2Time, RejectionTracker tracker) {
        return evaluate(symbol, date, candles, adRatio, dailyAtr, avgC1Volume, riskRupees,
                prevDayOpen, prevDayHigh, prevDayLow, prevDayClose, niftyVwap, niftyPrice, vix, atr5m,
                C1_TIME, c2Time, tracker);
    }

    /** Evaluates a setup using caller-supplied C1 and C2 candle times. */
    public Optional<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles,
                                            double adRatio, double dailyAtr, long avgC1Volume,
                                            double riskRupees, Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose,
                                            double niftyVwap, double niftyPrice, double vix, double atr5m,
                                            LocalTime c1Time, LocalTime c2Time, RejectionTracker tracker) {
        log.info("{} {} — evaluating {} candles | wr={} bodyPts={} bodyPct={}% atrRatio={} adRatio={} avgC1Vol={}",
                symbol, date, candles == null ? 0 : candles.size(),
                config.getMinWickRatio(), config.getMinCandleBodyPoints(),
                config.getMinC1BodyPct(), config.getMinC1AtrRatio(), adRatio, avgC1Volume);

        if (candles == null || candles.size() < 3) return Optional.empty();

        Candle c1 = findCandle(candles, c1Time);
        Candle c2 = findCandle(candles, c2Time);

        if (c1 == null || c2 == null) {
            log.info("{} {} — C1 or C2 not found", symbol, date);
            if (tracker != null) tracker.increment("C1/C2 Not Found");
            return Optional.empty();
        }

        // ── STEP 1: C1 qualification ─────────────────────────────────────────
        if (!isStrongCandle(c1, dailyAtr, avgC1Volume, tracker)) {
            log.info("{} {} — C1 WEAK: wr={} body={} bodyPct={}% range={} vol={} (atr={} avgVol={})",
                    symbol, date,
                    String.format("%.4f", c1.wickRatio()),
                    String.format("%.2f", c1.body()),
                    String.format("%.3f", c1.getOpen() > 0 ? c1.body() / c1.getOpen() * 100 : 0),
                    String.format("%.2f", c1.range()), c1.getVolume(), dailyAtr, avgC1Volume);
            return Optional.empty();
        }

        // ── Exhausted moves filter ─────────────────────────────────────────────
        if (atr5m > 0 && c1.range() > 2.0 * atr5m) {
            if (tracker != null) tracker.increment("Exhausted Move (ATR5m)");
            log.info("{} {} — EXHAUSTED MOVE: C1 range={} > 2x ATR5m={}",
                    symbol, date, String.format("%.2f", c1.range()), String.format("%.2f", atr5m));
            return Optional.empty();
        }

        Direction direction = c1.isBullish() ? Direction.BUY : Direction.SELL;

        // ── Gap exhaustion filter ───────────────────────────────────────────────
        if (prevDayClose != null) {
            double gapPct = direction == Direction.BUY
                    ? ((c1.getOpen() - prevDayClose) / prevDayClose) * 100.0
                    : ((prevDayClose - c1.getOpen()) / prevDayClose) * 100.0;

            double movedPct = direction == Direction.BUY
                    ? ((c1.getHigh() - prevDayClose) / prevDayClose) * 100.0
                    : ((prevDayClose - c1.getLow()) / prevDayClose) * 100.0;

            if (gapPct > 1.0 && movedPct > 1.5) {
                if (tracker != null) tracker.increment("Gap Exhaustion");
                log.info("{} {} — GAP EXHAUSTION: gap={} moved={} direction={}", symbol, date,
                        String.format("%.2f%%", gapPct), String.format("%.2f%%", movedPct), direction);
                return Optional.empty();
            }
        }

        // ── A/D trend filter ──────────────────────────────────────────────────
        if (adRatio >= 0) {
            boolean buyBlocked  = adRatio < 0.7 && direction == Direction.BUY;
            boolean sellBlocked = adRatio > 1.5 && direction == Direction.SELL;
            if (buyBlocked || sellBlocked) {
                if (tracker != null) tracker.increment("A/D Ratio Filter");
                log.info("{} {} — BLOCKED by A/D filter: adRatio={} direction={}", symbol, date, adRatio, direction);
                return Optional.empty();
            }
        }

        // ── Market trend filter ───────────────────────────────────────────────
        if (niftyVwap > 0 && niftyPrice > 0) {
            boolean niftyAboveVwap = niftyPrice > niftyVwap;
            boolean vixStable = vix > 0 && vix < 20; // VIX < 20 considered stable

            if (direction == Direction.BUY) {
                // BUY only when: Nifty above VWAP AND A/D > 1.5 AND VIX stable
                if (!niftyAboveVwap || (adRatio >= 0 && adRatio < 1.5) || !vixStable) {
                    if (tracker != null) tracker.increment("Market Trend Filter");
                    log.info("{} {} — BLOCKED by market trend filter: niftyAboveVwap={} adRatio={} vixStable={}",
                            symbol, date, niftyAboveVwap, adRatio, vixStable);
                    return Optional.empty();
                }
            } else {
                // SELL only when: Nifty below VWAP AND A/D < 0.67
                if (niftyAboveVwap || (adRatio >= 0 && adRatio >= 0.67)) {
                    if (tracker != null) tracker.increment("Market Trend Filter");
                    log.info("{} {} — BLOCKED by market trend filter: niftyAboveVwap={} adRatio={}",
                            symbol, date, niftyAboveVwap, adRatio);
                    return Optional.empty();
                }
            }
        }

        // ── STEP 2: C2 confirmation ───────────────────────────────────────────
        if (!isValidC2(c1, c2, direction)) {
            if (tracker != null) tracker.increment("C2 Failed");
            log.info("{} {} — C2 FAILED {} confirmation: fifty={} c2.low={} c2.high={} c2.vol={} c1.vol={}",
                    symbol, date, direction,
                    String.format("%.2f", (c1.getHigh() + c1.getLow()) / 2),
                    String.format("%.2f", c2.getLow()),
                    String.format("%.2f", c2.getHigh()),
                    c2.getVolume(), c1.getVolume());
            return Optional.empty();
        }

        // ── STEP 3: Trade levels ──────────────────────────────────────────────
        double slMarginFactor = config.getSlMarginPercent() / 100.0;
        double entry, sl, risk, target;

        if (direction == Direction.BUY) {
            entry  = c2.getClose();
            sl     = c2.getLow() * (1 - slMarginFactor);
            risk   = entry - sl;
            target = entry + (risk * config.getTargetRR());
        } else {
            entry  = c2.getClose();
            sl     = c2.getHigh() * (1 + slMarginFactor);
            risk   = sl - entry;
            target = entry - (risk * config.getTargetRR());
        }

        if (risk <= 0) {
            log.info("{} {} — invalid risk: {}", symbol, date, risk);
            return Optional.empty();
        }

        if (tracker != null) tracker.increment("Passed All Filters");

        log.info("{} {} — {} | Entry={} SL={} Target={} Risk={}pts partialAt={}R",
                symbol, date, direction,
                String.format("%.2f", entry), String.format("%.2f", sl),
                String.format("%.2f", target), String.format("%.2f", risk),
                config.getPartialExitRR());

        // ── STEP 4: Simulate ──────────────────────────────────────────────────
        return Optional.of(simulateTrade(symbol, date, direction, c1, c2,
                entry, sl, target, risk, candles, riskRupees, prevDayOpen, prevDayHigh, prevDayLow, prevDayClose, c2Time));
    }

    // =========================================================================
    // Step 1 — C1 strong candle check
    // =========================================================================

    private boolean isStrongCandle(Candle c, double dailyAtr, long avgC1Volume, RejectionTracker tracker) {
        if (c.body() < config.getMinCandleBodyPoints()) {
            if (tracker != null) tracker.increment("C1 Weak: Body Points");
            return false;
        }
        if (c.wickRatio() < config.getMinWickRatio()) {
            if (tracker != null) tracker.increment("C1 Weak: Wick Ratio");
            return false;
        }
        if (c.getOpen() > 0 && c.body() / c.getOpen() * 100.0 < config.getMinC1BodyPct()) {
            if (tracker != null) tracker.increment("C1 Weak: Body Pct");
            return false;
        }
        if (dailyAtr > 0 && c.range() < config.getMinC1AtrRatio() * dailyAtr) {
            if (tracker != null) tracker.increment("C1 Weak: ATR Ratio");
            return false;
        }
        if (avgC1Volume > 0 && c.getVolume() < config.getMinC1VolumeMultiplier() * avgC1Volume) {
            if (tracker != null) tracker.increment("C1 Weak: Volume");
            return false;
        }
        return true;
    }

    // =========================================================================
    // Step 2 — C2 confirmation
    // =========================================================================

    private boolean isValidC2(Candle c1, Candle c2, Direction direction) {
        // Improvement 3 — C2 volume must be lower than C1 volume.
        // A genuine consolidation candle has lower volume than the impulse candle.
        // If C2 volume >= C1 volume, participants are actively fighting the move — not pausing.
        if (c2.getVolume() >= c1.getVolume()) {
            log.debug("C2 vol={} >= C1 vol={} — not genuine consolidation", c2.getVolume(), c1.getVolume());
            return false;
        }

        double fifty   = c1.fiftyPercent();
        double c1Range = c1.range();
        double ext414  = c1Range * 0.414;

        if (direction == Direction.BUY) {
            boolean aboveFifty = c2.getLow() > fifty;
            double  extLevel   = c1.getHigh() + ext414;
            boolean withinExt  = c2.getHigh() <= extLevel;

            double  fib382   = c1.getHigh() - (c1Range * 0.382);
            boolean fib382ok = c2.getLow() <= fib382 ? c2.getClose() > fib382 : true;

            log.debug("BUY C2: aboveFifty={} withinExt={} fib382ok={}", aboveFifty, withinExt, fib382ok);
            return aboveFifty && withinExt && fib382ok;

        } else {
            boolean belowFifty = c2.getHigh() < fifty;
            double  extLevel   = c1.getLow() - ext414;
            boolean withinExt  = c2.getLow() >= extLevel;

            double  fib382   = c1.getLow() + (c1Range * 0.382);
            boolean fib382ok = c2.getHigh() >= fib382 ? c2.getClose() < fib382 : true;

            log.debug("SELL C2: belowFifty={} withinExt={} fib382ok={}", belowFifty, withinExt, fib382ok);
            return belowFifty && withinExt && fib382ok;
        }
    }

    // =========================================================================
    // Step 4 — Simulate trade
    //
    // Improvement 4 — Breakeven trail: once price hits partialExitRR (1.5R),
    //   move SL to entry (breakeven). Subsequent SL hit → BREAKEVEN_EXIT (0 loss).
    //
    // Improvement 5 — Partial exit: book partialExitQtyPct (50%) at partialExitRR (1.5R),
    //   let remaining 50% run to full target (2.5R).
    //   P&L = weighted average of both legs.
    //
    // Improvement 10 — riskRupees passed in (may be reduced after consecutive losses).
    // =========================================================================

    private BacktestTrade simulateTrade(
            String symbol, LocalDate date, Direction direction,
            Candle c1, Candle c2,
            double entry, double sl, double target, double risk,
            List<Candle> candles, double riskRupees,
            Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose, LocalTime c2Time) {

        double activeSl       = sl;
        double exitPrice      = entry;
        Outcome outcome       = Outcome.EOD_EXIT;
        java.time.LocalDateTime exitDt = null;

        double partialTarget  = direction == Direction.BUY
                ? entry + risk * config.getPartialExitRR()
                : entry - risk * config.getPartialExitRR();
        boolean partialHit    = false;
        double  partialExitPx = 0;
        double  partialPct    = config.getPartialExitQtyPct() / 100.0;
        double  remainPct     = 1.0 - partialPct;

        for (Candle c : candles) {
            if (!c.getTimestamp().toLocalTime().isAfter(c2Time)) continue;

            boolean isEod = !c.getTimestamp().toLocalTime().isBefore(EOD_TIME);

            if (direction == Direction.BUY) {
                // Partial exit + trail SL to breakeven
                if (!partialHit && c.getHigh() >= partialTarget) {
                    partialHit    = true;
                    partialExitPx = partialTarget;
                    activeSl      = entry;
                    log.debug("{} BUY partial @ {} SL → BE {}", symbol, partialTarget, entry);
                }
                if (c.getLow() <= activeSl) {
                    exitPrice = activeSl;
                    outcome   = partialHit ? Outcome.BREAKEVEN_EXIT : Outcome.SL_HIT;
                    exitDt    = c.getTimestamp(); break;
                }
                if (c.getHigh() >= target) {
                    exitPrice = target; outcome = Outcome.TARGET_HIT; exitDt = c.getTimestamp(); break;
                }
            } else {
                if (!partialHit && c.getLow() <= partialTarget) {
                    partialHit    = true;
                    partialExitPx = partialTarget;
                    activeSl      = entry;
                    log.debug("{} SELL partial @ {} SL → BE {}", symbol, partialTarget, entry);
                }
                if (c.getHigh() >= activeSl) {
                    exitPrice = activeSl;
                    outcome   = partialHit ? Outcome.BREAKEVEN_EXIT : Outcome.SL_HIT;
                    exitDt    = c.getTimestamp(); break;
                }
                if (c.getLow() <= target) {
                    exitPrice = target; outcome = Outcome.TARGET_HIT; exitDt = c.getTimestamp(); break;
                }
            }

            if (isEod) {
                exitPrice = c.getClose(); outcome = Outcome.EOD_EXIT; exitDt = c.getTimestamp(); break;
            }
        }

        // ── P&L — weighted across partial + remainder legs ────────────────────
        // C3: first candle closing above C2 high (BUY) or below C2 low (SELL)
        Candle c3 = null;
        for (Candle c : candles) {
            if (!c.getTimestamp().toLocalTime().isAfter(c2Time)) continue;
            if (direction == Direction.BUY && c.getClose() > c2.getHigh()) { c3 = c; break; }
            if (direction == Direction.SELL && c.getClose() < c2.getLow()) { c3 = c; break; }
        }

        int quantity = risk > 0 ? (int) Math.floor(riskRupees / risk) : 0;

        double pnlPoints;
        if (partialHit && partialExitPx > 0) {
            double partialLegPnl = direction == Direction.BUY
                    ? (partialExitPx - entry) * partialPct
                    : (entry - partialExitPx) * partialPct;
            double remainLegPnl  = direction == Direction.BUY
                    ? (exitPrice - entry) * remainPct
                    : (entry - exitPrice) * remainPct;
            pnlPoints = partialLegPnl + remainLegPnl;
        } else {
            pnlPoints = direction == Direction.BUY ? exitPrice - entry : entry - exitPrice;
        }

        double pnlRupees  = pnlPoints * quantity;
        double pnlPercent = entry > 0 ? (pnlPoints / entry) * 100.0 : 0.0;
        double actualRR   = risk  > 0 ? pnlPoints / risk             : 0.0;

        log.info("  {} {} qty={} risk={} pnl₹={} partial={} ({})",
                symbol, direction, quantity,
                String.format("%.2f", risk),
                String.format("%.0f", pnlRupees),
                partialHit, outcome);

        return BacktestTrade.builder()
                .symbol(symbol).tradeDate(date).direction(direction)
                .c1Open(c1.getOpen()).c1High(c1.getHigh()).c1Low(c1.getLow())
                .c1Close(c1.getClose()).c1WickRatio(c1.wickRatio())
                .c2Open(c2.getOpen()).c2High(c2.getHigh()).c2Low(c2.getLow()).c2Close(c2.getClose())
                .entryPrice(entry).stopLoss(activeSl).target(target)
                .riskPoints(risk).rewardPoints(Math.abs(target - entry))
                .quantity(quantity)
                .riskRupees((double) quantity * risk)
                .pnlRupees(pnlRupees)
                .outcome(outcome).exitPrice(exitPrice)
                .pnlPoints(pnlPoints).pnlPercent(pnlPercent).actualRR(actualRR)
                .exitCandleTime(exitDt)
                .prevDayOpen(prevDayOpen)
                .prevDayHigh(prevDayHigh)
                .prevDayLow(prevDayLow)
                .prevDayClose(prevDayClose)
                .c1AbovePrevHigh(prevDayHigh != null && c1.getOpen() > prevDayHigh)
                .c1AbovePrevLow(prevDayLow != null && c1.getOpen() > prevDayLow)
                .c3Open(c3 != null ? c3.getOpen() : null)
                .c3High(c3 != null ? c3.getHigh() : null)
                .c3Low(c3 != null ? c3.getLow() : null)
                .c3Close(c3 != null ? c3.getClose() : null)
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    private Candle findCandle(List<Candle> candles, LocalTime time) {
        return candles.stream()
                .filter(c -> c.getTimestamp().toLocalTime().equals(time))
                .findFirst().orElse(null);
    }
}
