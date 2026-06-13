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

    private static final LocalTime C1_TIME  = LocalTime.of(9, 15);
    private static final LocalTime C2_TIME  = LocalTime.of(9, 30);
    private static final LocalTime EOD_TIME = LocalTime.of(15, 15);

    private final BacktestConfig config;

    /** Backward-compatible overload — uses full fixedRiskRupees, no external filters */
    public Optional<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles) {
        return evaluate(symbol, date, candles, -1.0, 0.0, 0, config.getFixedRiskRupees());
    }

    /**
     * @param adRatio     A/D ratio (-1 = unavailable, skip filter)
     * @param dailyAtr    20-day ATR in points (0 = skip filter)
     * @param avgC1Volume 5-day avg 9:15 candle volume (0 = skip filter)
     * @param riskRupees  risk in rupees — may be reduced after consecutive losses (Improvement 10)
     */
    public Optional<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles,
                                            double adRatio, double dailyAtr, long avgC1Volume,
                                            double riskRupees) {
        log.info("{} {} — evaluating {} candles | wr={} bodyPts={} bodyPct={}% atrRatio={} adRatio={} avgC1Vol={}",
                symbol, date, candles == null ? 0 : candles.size(),
                config.getMinWickRatio(), config.getMinCandleBodyPoints(),
                config.getMinC1BodyPct(), config.getMinC1AtrRatio(), adRatio, avgC1Volume);

        if (candles == null || candles.size() < 3) return Optional.empty();

        Candle c1 = findCandle(candles, C1_TIME);
        Candle c2 = findCandle(candles, C2_TIME);

        if (c1 == null || c2 == null) {
            log.info("{} {} — C1 or C2 not found", symbol, date);
            return Optional.empty();
        }

        // ── STEP 1: C1 qualification ─────────────────────────────────────────
        if (!isStrongCandle(c1, dailyAtr, avgC1Volume)) {
            log.info("{} {} — C1 WEAK: wr={} body={} bodyPct={}% range={} vol={} (atr={} avgVol={})",
                    symbol, date,
                    String.format("%.4f", c1.wickRatio()),
                    String.format("%.2f", c1.body()),
                    String.format("%.3f", c1.getOpen() > 0 ? c1.body() / c1.getOpen() * 100 : 0),
                    String.format("%.2f", c1.range()), c1.getVolume(), dailyAtr, avgC1Volume);
            return Optional.empty();
        }

        Direction direction = c1.isBullish() ? Direction.BUY : Direction.SELL;

        // ── A/D trend filter ──────────────────────────────────────────────────
        if (adRatio >= 0) {
            boolean buyBlocked  = adRatio < 0.7 && direction == Direction.BUY;
            boolean sellBlocked = adRatio > 1.5 && direction == Direction.SELL;
            if (buyBlocked || sellBlocked) {
                log.info("{} {} — BLOCKED by A/D filter: adRatio={} direction={}", symbol, date, adRatio, direction);
                return Optional.empty();
            }
        }

        // ── STEP 2: C2 confirmation ───────────────────────────────────────────
        if (!isValidC2(c1, c2, direction)) {
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

        log.info("{} {} — {} | Entry={} SL={} Target={} Risk={}pts partialAt={}R",
                symbol, date, direction,
                String.format("%.2f", entry), String.format("%.2f", sl),
                String.format("%.2f", target), String.format("%.2f", risk),
                config.getPartialExitRR());

        // ── STEP 4: Simulate ──────────────────────────────────────────────────
        return Optional.of(simulateTrade(symbol, date, direction, c1, c2,
                entry, sl, target, risk, candles, riskRupees));
    }

    // =========================================================================
    // Step 1 — C1 strong candle check
    // =========================================================================

    private boolean isStrongCandle(Candle c, double dailyAtr, long avgC1Volume) {
        if (c.body() < config.getMinCandleBodyPoints()) return false;
        if (c.wickRatio() < config.getMinWickRatio()) return false;
        if (c.getOpen() > 0 && c.body() / c.getOpen() * 100.0 < config.getMinC1BodyPct()) return false;
        if (dailyAtr > 0 && c.range() < config.getMinC1AtrRatio() * dailyAtr) return false;
        if (avgC1Volume > 0 && c.getVolume() < config.getMinC1VolumeMultiplier() * avgC1Volume) return false;
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
            List<Candle> candles, double riskRupees) {

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
            if (!c.getTimestamp().toLocalTime().isAfter(C2_TIME)) continue;

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
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    private Candle findCandle(List<Candle> candles, LocalTime time) {
        return candles.stream()
                .filter(c -> c.getTimestamp().toLocalTime().equals(time))
                .findFirst().orElse(null);
    }
}
