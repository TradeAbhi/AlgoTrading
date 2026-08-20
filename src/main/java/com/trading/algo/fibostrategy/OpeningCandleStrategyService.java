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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opening Candle Strategy:
 *
 * STEP 1 — C1 (9:15) qualification (unchanged):
 *   wick ratio >= minWickRatio | body >= minCandleBodyPoints | body >= minC1BodyPct% of open
 *   range >= minC1AtrRatio × 20-day ATR | volume >= minC1VolumeMultiplier × avg C1 volume
 *
 * STEP 1.5 — C2 volume gate (RESTORED per instruction):
 *   C2 volume MUST be < C1 volume (genuine consolidation, not distribution).
 *   If this fails, the entire day is rejected before the BO scan runs -
 *   same role it played in the original isValidC2 check.
 *
 * STEP 2 — Breakout (BO) candle identification:
 *   Track a running extreme (highest high for BUY / lowest low for SELL)
 *   starting at C1's own high/low. Scan candles strictly after C1, in
 *   order. The BO candle is the first one whose CLOSE breaks past that
 *   running extreme. No entries allowed after LAST_ENTRY_TIME (14:30).
 *
 * STEP 3 — Entry: at the BO candle's CLOSE.
 *
 * STEP 4 — SL: BOcandle low/high ± margin, where margin = (BOcandle high -
 *   BOcandle low) / 2.
 *
 * STEP 5 — Position size: if entry falls WITHIN the immediately preceding
 *   candle's high/low range, halve position size. BUY side only.
 *
 * STEP 6 — Same-day re-entry (FIXED per feedback):
 *   If the primary trade exits via SL_HIT, compute the FAILURE EXTREME -
 *   the highest high (BUY) / lowest low (SELL) reached across every candle
 *   from the BO candle through to (and including) the SL-hit candle, NOT
 *   just the BO candle's own high/low. Then watch (once only) for a later
 *   candle closing beyond that failure extreme. If found, that candle
 *   becomes a new BO candle and steps 3-5 repeat for it. Capped at exactly
 *   one re-entry per day.
 *
 * STEP 7 — Simulate: partial exit at partialExitRR, breakeven trail after
 *   partial, time-based SL trail. EOD exit at 3:00 PM.
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

    private static final LocalTime LAST_ENTRY_TIME = LocalTime.of(14, 30);
    private static final LocalTime EOD_TIME = LocalTime.of(15, 0);

    private final BacktestConfig config;

    public List<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles) {
        return evaluate(symbol, date, candles, -1.0, 0.0, 0, config.getFixedRiskRupees(), null, null, null, null, 0.0, 0.0, 0.0, 0.0, C2_TIME, null);
    }

    public List<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles,
                                        double adRatio, double dailyAtr, long avgC1Volume,
                                        double riskRupees, Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose,
                                        double niftyVwap, double niftyPrice, double vix, double atr5m) {
        return evaluate(symbol, date, candles, adRatio, dailyAtr, avgC1Volume, riskRupees, prevDayOpen, prevDayHigh, prevDayLow, prevDayClose, niftyVwap, niftyPrice, vix, atr5m, C2_TIME, null);
    }

    public List<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles,
                                        double adRatio, double dailyAtr, long avgC1Volume,
                                        double riskRupees, Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose,
                                        double niftyVwap, double niftyPrice, double vix, double atr5m,
                                        LocalTime c2Time, RejectionTracker tracker) {
        return evaluate(symbol, date, candles, adRatio, dailyAtr, avgC1Volume, riskRupees,
                prevDayOpen, prevDayHigh, prevDayLow, prevDayClose, niftyVwap, niftyPrice, vix, atr5m,
                C1_TIME, c2Time, tracker);
    }

    public List<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles,
                                        double adRatio, double dailyAtr, long avgC1Volume,
                                        double riskRupees, Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose,
                                        double niftyVwap, double niftyPrice, double vix, double atr5m,
                                        LocalTime c1Time, LocalTime c2Time, RejectionTracker tracker) {
        log.info("{} {} — evaluating {} candles | wr={} bodyPts={} bodyPct={}% atrRatio={} adRatio={} avgC1Vol={}",
                symbol, date, candles == null ? 0 : candles.size(),
                config.getMinWickRatio(), config.getMinCandleBodyPoints(),
                config.getMinC1BodyPct(), config.getMinC1AtrRatio(), adRatio, avgC1Volume);

        if (candles == null || candles.size() < 3) return List.of();

        List<Candle> sorted = candles.stream()
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();

        Candle c1 = findCandle(sorted, c1Time);
        Candle c2 = findCandle(sorted, c2Time);

        if (c1 == null || c2 == null) {
            log.info("{} {} — C1 or C2 not found", symbol, date);
            if (tracker != null) tracker.increment("C1/C2 Not Found");
            return List.of();
        }

        // ── STEP 1: C1 qualification ─────────────────────────────────────────
        if (!isStrongCandle(c1, dailyAtr, avgC1Volume, tracker)) {
            log.info("{} {} — C1 WEAK: wr={} body={} bodyPct={}% range={} vol={} (atr={} avgVol={})",
                    symbol, date,
                    String.format("%.4f", c1.wickRatio()),
                    String.format("%.2f", c1.body()),
                    String.format("%.3f", c1.getOpen() > 0 ? c1.body() / c1.getOpen() * 100 : 0),
                    String.format("%.2f", c1.range()), c1.getVolume(), dailyAtr, avgC1Volume);
            return List.of();
        }

        // ── Exhausted moves filter ─────────────────────────────────────────────
        if (atr5m > 0 && c1.range() > 2.0 * atr5m) {
            if (tracker != null) tracker.increment("Exhausted Move (ATR5m)");
            log.info("{} {} — EXHAUSTED MOVE: C1 range={} > 2x ATR5m={}",
                    symbol, date, String.format("%.2f", c1.range()), String.format("%.2f", atr5m));
            return List.of();
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
                return List.of();
            }
        }

        // ── A/D trend filter ──────────────────────────────────────────────────
        if (adRatio >= 0) {
            boolean buyBlocked  = adRatio < 0.7 && direction == Direction.BUY;
            boolean sellBlocked = adRatio > 1.5 && direction == Direction.SELL;
            if (buyBlocked || sellBlocked) {
                if (tracker != null) tracker.increment("A/D Ratio Filter");
                log.info("{} {} — BLOCKED by A/D filter: adRatio={} direction={}", symbol, date, adRatio, direction);
                return List.of();
            }
        }

        // ── Market trend filter ───────────────────────────────────────────────
        if (niftyVwap > 0 && niftyPrice > 0) {
            boolean niftyAboveVwap = niftyPrice > niftyVwap;
            boolean vixStable = vix > 0 && vix < 20;

            if (direction == Direction.BUY) {
                if (!niftyAboveVwap || (adRatio >= 0 && adRatio < 1.5) || !vixStable) {
                    if (tracker != null) tracker.increment("Market Trend Filter");
                    log.info("{} {} — BLOCKED by market trend filter: niftyAboveVwap={} adRatio={} vixStable={}",
                            symbol, date, niftyAboveVwap, adRatio, vixStable);
                    return List.of();
                }
            } else {
                if (niftyAboveVwap || (adRatio >= 0 && adRatio >= 0.67)) {
                    if (tracker != null) tracker.increment("Market Trend Filter");
                    log.info("{} {} — BLOCKED by market trend filter: niftyAboveVwap={} adRatio={}",
                            symbol, date, niftyAboveVwap, adRatio);
                    return List.of();
                }
            }
        }

        // ── STEP 1.5: C2 volume gate (RESTORED) ─────────────────────────────
        if (c2.getVolume() >= c1.getVolume()) {
            if (tracker != null) tracker.increment("C2 Failed: Volume");
            log.info("{} {} — C2 FAILED volume check: c2.vol={} >= c1.vol={} (not genuine consolidation)",
                    symbol, date, c2.getVolume(), c1.getVolume());
            return List.of();
        }

        if (tracker != null) tracker.increment("Passed Pre-Filters");

        // ── STEP 2-6: BO candle scan, entry, SL, sizing, re-entry chain ──────
        return buildTradeChain(symbol, date, direction, c1, c2, sorted,
                riskRupees, prevDayOpen, prevDayHigh, prevDayLow, prevDayClose, tracker);
    }

    // =========================================================================
    // Step 1 — C1 strong candle check (unchanged)
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
    // Steps 2-6 — BO candle scan / entry / SL / sizing / re-entry chain
    // =========================================================================

    private List<BacktestTrade> buildTradeChain(
            String symbol, LocalDate date, Direction direction,
            Candle c1, Candle c2, List<Candle> sorted,
            double riskRupees, Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose,
            RejectionTracker tracker) {

        List<BacktestTrade> chain = new ArrayList<>();

        Optional<Candle> primaryBo = findInitialBreakoutCandle(sorted, c1, direction);

        if (primaryBo.isEmpty()) {
            if (tracker != null) tracker.increment("No Breakout Candle Found");
            log.info("{} {} — no BO candle found before {} ({})", symbol, date, LAST_ENTRY_TIME, direction);
            return chain;
        }

        BacktestTrade primaryTrade = openAndSimulateTrade(
                symbol, date, direction, c1, c2, primaryBo.get(), sorted,
                riskRupees, prevDayOpen, prevDayHigh, prevDayLow, prevDayClose,
                0 /* reEntrySequence */, null /* reEntryTriggerLevel */);

        chain.add(primaryTrade);

        // ---- Rule 7 (FIXED): re-entry level is the highest high (BUY) /
        // lowest low (SELL) across the WHOLE failed attempt - from the BO
        // candle through to the SL-hit candle inclusive - not just the BO
        // candle's own high/low. ----
        if (primaryTrade.getOutcome() == Outcome.SL_HIT && primaryTrade.getExitCandleTime() != null) {

            double failureExtreme = computeFailureExtremeLevel(
                    sorted, primaryBo.get(), primaryTrade.getExitCandleTime(), direction);

            Optional<Candle> reEntryBo = findReEntryCandle(
                    sorted, failureExtreme, primaryTrade.getExitCandleTime(), direction);

            if (reEntryBo.isPresent()) {

                if (tracker != null) tracker.increment("Re-Entry Taken");

                log.info("{} {} — re-entry armed: failureExtreme={} ({}), triggered by candle@{} close={}",
                        symbol, date, String.format("%.2f", failureExtreme), direction,
                        reEntryBo.get().getTimestamp().toLocalTime(),
                        String.format("%.2f", reEntryBo.get().getClose()));

                BacktestTrade reEntryTrade = openAndSimulateTrade(
                        symbol, date, direction, c1, c2, reEntryBo.get(), sorted,
                        riskRupees, prevDayOpen, prevDayHigh, prevDayLow, prevDayClose,
                        1 /* reEntrySequence */, failureExtreme);

                chain.add(reEntryTrade);
            }
        }

        return chain;
    }

    /**
     * Rules 1 & 2 (unified): starting extreme = C1's own high (BUY) / low
     * (SELL). First candle after C1 whose CLOSE breaks the running extreme
     * is the BO candle.
     */
    private Optional<Candle> findInitialBreakoutCandle(List<Candle> sorted, Candle c1, Direction direction) {

        double runningExtreme = direction == Direction.BUY ? c1.getHigh() : c1.getLow();

        for (Candle c : sorted) {

            if (!c.getTimestamp().isAfter(c1.getTimestamp())) continue;
            if (c.getTimestamp().toLocalTime().isAfter(LAST_ENTRY_TIME)) break;

            boolean triggered = direction == Direction.BUY
                    ? c.getClose() > runningExtreme
                    : c.getClose() < runningExtreme;

            if (triggered) {
                return Optional.of(c);
            }

            runningExtreme = direction == Direction.BUY
                    ? Math.max(runningExtreme, c.getHigh())
                    : Math.min(runningExtreme, c.getLow());
        }

        return Optional.empty();
    }

    /**
     * FIXED (per feedback): the failure extreme is the highest high (BUY) /
     * lowest low (SELL) across EVERY candle from the BO candle through to
     * and including the SL-hit candle - not just the BO candle's own
     * high/low. This captures how far price actually pushed in the trade's
     * favor before reversing to stop it out.
     */
    private double computeFailureExtremeLevel(
            List<Candle> sorted, Candle bo, LocalDateTime slHitTime, Direction direction) {

        double extreme = direction == Direction.BUY ? bo.getHigh() : bo.getLow();

        for (Candle c : sorted) {

            if (c.getTimestamp().isBefore(bo.getTimestamp())) continue;
            if (c.getTimestamp().isAfter(slHitTime)) break;

            extreme = direction == Direction.BUY
                    ? Math.max(extreme, c.getHigh())
                    : Math.min(extreme, c.getLow());
        }

        return extreme;
    }

    /**
     * Rule 7: scan strictly after the SL-hit candle for the first candle
     * whose CLOSE breaks the failure extreme level. Bounded by
     * LAST_ENTRY_TIME. Only ever called once per day (enforced by caller).
     */
    private Optional<Candle> findReEntryCandle(
            List<Candle> sorted, double failureExtreme, LocalDateTime resumeAfter, Direction direction) {

        for (Candle c : sorted) {

            if (!c.getTimestamp().isAfter(resumeAfter)) continue;
            if (c.getTimestamp().toLocalTime().isAfter(LAST_ENTRY_TIME)) break;

            boolean triggered = direction == Direction.BUY
                    ? c.getClose() > failureExtreme
                    : c.getClose() < failureExtreme;

            if (triggered) {
                return Optional.of(c);
            }
        }

        return Optional.empty();
    }

    private BacktestTrade openAndSimulateTrade(
            String symbol, LocalDate date, Direction direction,
            Candle c1, Candle c2, Candle bo, List<Candle> sorted,
            double riskRupees, Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose,
            int reEntrySequence, Double reEntryTriggerLevel) {

        double entry = bo.getClose();

        double boMargin = (bo.getHigh() - bo.getLow()) / 2.0;

        double sl, risk, target;

        if (direction == Direction.BUY) {
            sl     = bo.getLow() - boMargin;
            risk   = entry - sl;
            target = entry + (risk * config.getTargetRR());
        } else {
            sl     = bo.getHigh() + boMargin;
            risk   = sl - entry;
            target = entry - (risk * config.getTargetRR());
        }

        double sizeMultiplier = 1.0;
        boolean withinPrevCandleRange = false;

        if (direction == Direction.BUY) {
            Optional<Candle> prevOpt = previousCandle(sorted, bo);
            if (prevOpt.isPresent()) {
                Candle prev = prevOpt.get();
                withinPrevCandleRange = entry >= prev.getLow() && entry <= prev.getHigh();
                if (withinPrevCandleRange) {
                    sizeMultiplier = 0.5;
                }
            }
        }

        log.info("{} {} — {} BO@{} | Entry={} SL={} Target={} Risk={}pts size={}x reEntry={} c1Vol={} c2Vol={}",
                symbol, date, direction, bo.getTimestamp().toLocalTime(),
                String.format("%.2f", entry), String.format("%.2f", sl),
                String.format("%.2f", target), String.format("%.2f", risk),
                sizeMultiplier, reEntrySequence, c1.getVolume(), c2.getVolume());

        return simulateTrade(symbol, date, direction, c1, c2, bo,
                entry, sl, target, risk, sizeMultiplier, sorted, riskRupees,
                prevDayOpen, prevDayHigh, prevDayLow, prevDayClose, reEntrySequence, reEntryTriggerLevel);
    }

    private Optional<Candle> previousCandle(List<Candle> sorted, Candle target) {
        int idx = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getTimestamp().equals(target.getTimestamp())) {
                idx = i;
                break;
            }
        }
        return (idx > 0) ? Optional.of(sorted.get(idx - 1)) : Optional.empty();
    }

    // =========================================================================
    // Step 7 — Simulate trade
    // =========================================================================

    private BacktestTrade simulateTrade(
            String symbol, LocalDate date, Direction direction,
            Candle c1, Candle c2, Candle bo,
            double entry, double sl, double target, double risk, double sizeMultiplier,
            List<Candle> candles, double riskRupees,
            Double prevDayOpen, Double prevDayHigh, Double prevDayLow, Double prevDayClose,
            int reEntrySequence, Double reEntryTriggerLevel) {

        double activeSl       = sl;
        double exitPrice      = entry;
        Outcome outcome       = Outcome.EOD_EXIT;
        LocalDateTime exitDt = null;

        double partialTarget  = direction == Direction.BUY
                ? entry + risk * config.getPartialExitRR()
                : entry - risk * config.getPartialExitRR();
        boolean partialHit    = false;
        double  partialExitPx = 0;
        double  partialPct    = config.getPartialExitQtyPct() / 100.0;
        double  remainPct     = 1.0 - partialPct;
        int candlesAfterEntry = 0;
        double highestSinceEntry = entry;
        double lowestSinceEntry = entry;

        for (Candle c : candles) {
            if (!c.getTimestamp().isAfter(bo.getTimestamp())) continue;
            candlesAfterEntry++;

            boolean isEod = !c.getTimestamp().toLocalTime().isBefore(EOD_TIME);

            if (direction == Direction.BUY) {
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

            highestSinceEntry = Math.max(highestSinceEntry, c.getHigh());
            lowestSinceEntry = Math.min(lowestSinceEntry, c.getLow());
            if (candlesAfterEntry >= config.getTimeBasedSlTrailCandles()) {
                double margin = config.getTimeBasedSlTrailMarginPercent() / 100.0;
                if (direction == Direction.BUY) {
                    double trailedStop = highestSinceEntry * (1.0 - margin);
                    if (trailedStop > activeSl) {
                        activeSl = trailedStop;
                        log.debug("{} BUY time trail after {} candles: SL={}", symbol, candlesAfterEntry, activeSl);
                    }
                } else {
                    double trailedStop = lowestSinceEntry * (1.0 + margin);
                    if (trailedStop < activeSl) {
                        activeSl = trailedStop;
                        log.debug("{} SELL time trail after {} candles: SL={}", symbol, candlesAfterEntry, activeSl);
                    }
                }
            }
        }

        // Find 3 PM candle for end-of-day price analysis
        Double pm3Price = null;
        for (Candle c : candles) {
            if (c.getTimestamp().toLocalTime().equals(EOD_TIME)) {
                pm3Price = c.getClose();
                break;
            }
        }

        Candle c3 = null;
        for (Candle c : candles) {
            if (!c.getTimestamp().isAfter(bo.getTimestamp())) continue;
            if (direction == Direction.BUY && c.getClose() > bo.getHigh()) { c3 = c; break; }
            if (direction == Direction.SELL && c.getClose() < bo.getLow()) { c3 = c; break; }
        }

        int fullQuantity = risk > 0 ? (int) Math.floor(riskRupees / risk) : 0;
        int quantity = (int) Math.floor(fullQuantity * sizeMultiplier);

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

        log.info("  {} {} qty={} risk={} pnl₹={} partial={} reEntry={} ({})",
                symbol, direction, quantity,
                String.format("%.2f", risk),
                String.format("%.0f", pnlRupees),
                partialHit, reEntrySequence, outcome);

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
                .c1AbovePrevHigh(prevDayHigh != null && entry > prevDayHigh)
                .c1AbovePrevLow(prevDayLow != null && entry > prevDayLow)
                .c3Open(c3 != null ? c3.getOpen() : null)
                .c3High(c3 != null ? c3.getHigh() : null)
                .c3Low(c3 != null ? c3.getLow() : null)
                .c3Close(c3 != null ? c3.getClose() : null)
                .createdAt(java.time.LocalDateTime.now())
                // ---- new fields - add to BacktestTrade/@Builder ----
                .boOpen(bo.getOpen())
                .boHigh(bo.getHigh())
                .boLow(bo.getLow())
                .boClose(bo.getClose())
                .boTime(bo.getTimestamp())
                .reEntrySequence(reEntrySequence)
                .reEntryTriggerLevel(reEntryTriggerLevel)   // null for primary trade
                .positionSizeMultiplier(sizeMultiplier)
                .c1Volume(c1.getVolume())
                .c2Volume(c2.getVolume())
                .pm3Price(pm3Price)
                .build();
    }

    private Candle findCandle(List<Candle> candles, LocalTime time) {
        return candles.stream()
                .filter(c -> c.getTimestamp().toLocalTime().equals(time))
                .findFirst().orElse(null);
    }
}