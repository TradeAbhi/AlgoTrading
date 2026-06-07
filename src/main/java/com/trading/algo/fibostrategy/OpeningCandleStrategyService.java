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
 * Opening Candle Strategy — corrected rules:
 *
 * STEP 1 — C1 (9:15 candle) qualification:
 *   - Strong directional candle: wick ratio >= minWickRatio (default 0.75)
 *   - Body size >= minCandleBodyPoints
 *   - Bullish C1 → BUY setup, Bearish C1 → SELL setup
 *
 * STEP 2 — C2 (9:30 candle) confirmation:
 *   BULLISH (BUY):
 *     - C2.low  > 50% of C1  → entire C2 stays ABOVE midpoint (no deep pullback)
 *     - C2.range <= 1.41% of C1.low → C2 is a tight consolidation candle
 *   BEARISH (SELL):
 *     - C2.high < 50% of C1  → entire C2 stays BELOW midpoint (no deep recovery)
 *     - C2.range <= 1.41% of C1.high → C2 is a tight consolidation candle
 *
 * STEP 3 — Entry at C2 close:
 *   BUY  : SL = C2.low  - 0.015%,  Target = Entry + risk × 2.5
 *   SELL : SL = C2.high + 0.015%,  Target = Entry - risk × 2.5
 *
 * STEP 4 — Simulate on subsequent candles until TARGET, SL, or EOD
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpeningCandleStrategyService {

    private static final LocalTime C1_TIME  = LocalTime.of(9, 15);
    private static final LocalTime C2_TIME  = LocalTime.of(9, 30);
    private static final LocalTime EOD_TIME = LocalTime.of(15, 15); // force close at 3:15 PM

    private final BacktestConfig config;

    public Optional<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles) {
        log.info("{} {} — evaluating {} candles | config: wickRatio={} bodyPts={}", symbol, date,
                candles == null ? 0 : candles.size(), config.getMinWickRatio(), config.getMinCandleBodyPoints());
        if (candles == null || candles.size() < 3) {
            return Optional.empty();
        }

        Candle c1 = findCandle(candles, C1_TIME);
        Candle c2 = findCandle(candles, C2_TIME);

        if (c1 == null || c2 == null) {
            log.info("{} {} — C1 or C2 not found in candles list", symbol, date);
            return Optional.empty();
        }

        // ── STEP 1: C1 must be a strong directional candle ───────────────────
        if (!isStrongCandle(c1)) {
            log.info("{} {} — C1 WEAK: wickRatio={} body={} (need ratio>={} body>={})", symbol, date,
                    String.format("%.4f", c1.wickRatio()), String.format("%.2f", c1.body()),
                    config.getMinWickRatio(), config.getMinCandleBodyPoints());
            return Optional.empty();
        }

        Direction direction = c1.isBullish() ? Direction.BUY : Direction.SELL;

        // ── STEP 2: C2 confirmation ───────────────────────────────────────────
        if (!isValidC2(c1, c2, direction)) {
            log.info("{} {} — C2 FAILED {} confirmation: fifty={} c2.low={} c2.high={} c2.close={}", symbol, date, direction,
                    String.format("%.2f", (c1.getHigh()+c1.getLow())/2),
                    String.format("%.2f", c2.getLow()), String.format("%.2f", c2.getHigh()),
                    String.format("%.2f", c2.getClose()));
            return Optional.empty();
        }

        // ── STEP 3: Trade levels — SL from C2 high/low ───────────────────────
        double slMarginFactor = config.getSlMarginPercent() / 100.0;  // 0.45%
        double entry, sl, risk, target;

        if (direction == Direction.BUY) {
            entry  = c2.getClose();
            sl     = c2.getLow() * (1 - slMarginFactor);   // C2 low - 0.015%
            risk   = entry - sl;
            target = entry + (risk * config.getTargetRR());
        } else {
            entry  = c2.getClose();
            sl     = c2.getHigh() * (1 + slMarginFactor);  // C2 high + 0.015%
            risk   = sl - entry;
            target = entry - (risk * config.getTargetRR());
        }

        if (risk <= 0) {
            log.info("{} {} — invalid risk: {}", symbol, date, risk);
            return Optional.empty();
        }

        log.info("{} {} — {} | Entry={} SL={} Target={} Risk={}pts C2range={}%",
                symbol, date, direction,
                String.format("%.2f", entry),
                String.format("%.2f", sl),
                String.format("%.2f", target),
                String.format("%.2f", risk),
                String.format("%.3f", (c2.range() / c2.getHigh()) * 100));

        // ── STEP 4: Simulate ──────────────────────────────────────────────────
        BacktestTrade trade = simulateTrade(symbol, date, direction,
                c1, c2, entry, sl, target, risk, candles);

        return Optional.of(trade);
    }

    // =========================================================================
    // Step 1 — C1 strong candle check
    // =========================================================================

    private boolean isStrongCandle(Candle c) {
        return c.body()      >= config.getMinCandleBodyPoints()
            && c.wickRatio() >= config.getMinWickRatio();
    }

    // =========================================================================
    // Step 2 — C2 confirmation
    // =========================================================================

    /**
     * BULLISH setup (BUY):
     *   Rule 1 — C2 entirely ABOVE 50% of C1:
     *     C2.low > (C1.high + C1.low) / 2
     *   Rule 2 — C2 must NOT break below the 1.414 Fibonacci extension:
     *     C2.low >= C1.low - (C1.range x 0.414)
     *
     * BEARISH setup (SELL):
     *   Rule 1 — C2 entirely BELOW 50% of C1:
     *     C2.high < (C1.high + C1.low) / 2
     *   Rule 2 — C2 must NOT break above the 1.414 Fibonacci extension:
     *     C2.high <= C1.high + (C1.range x 0.414)
     *
     * Verified: C1.high=23221.35 C1.low=23062.60 -> ext414=23287.07 matches chart exactly.
     */
    private boolean isValidC2(Candle c1, Candle c2, Direction direction) {
        double fifty   = c1.fiftyPercent();
        double c1Range = c1.range();
        double ext414  = c1Range * 0.414;

        if (direction == Direction.BUY) {
            // Rule 1 — C2 entirely above 50% midpoint
            boolean aboveFifty = c2.getLow() > fifty;

            // Rule 2 — C2 high must not exceed 1.414 extension ABOVE C1 high
            // BUY setup: C2 is consolidating above 50%. It may push above C1 high slightly,
            // but if it goes beyond 1.414 ext upward, price is running away too fast
            // and there is no clean pullback entry.
            // 1.414 level = C1.high + (C1.range × 0.414)
            double  extLevel  = c1.getHigh() + ext414;
            boolean withinExt = c2.getHigh() <= extLevel;

            // Rule 3 — 38.2% filter (only activates if C2 touches the 38.2% level)
            // 38.2% level measured from TOP of C1 downward
            // = C1.high - (C1.range × 0.382)
            double  fib382 = c1.getHigh() - (c1Range * 0.382);
            boolean fib382ok;
            if (c2.getLow() <= fib382) {
                // C2 touched or crossed 38.2% — must CLOSE above it (rejection candle)
                fib382ok = c2.getClose() > fib382;
                log.debug("BUY 38% touched: fib382={} C2.low={} C2.close={} rejection={}",
                        String.format("%.2f", fib382),
                        String.format("%.2f", c2.getLow()),
                        String.format("%.2f", c2.getClose()), fib382ok);
            } else {
                // C2 didn't touch 38.2% — no filter needed, pass through
                fib382ok = true;
                log.debug("BUY 38% not touched: fib382={} C2.low={} — no filter applied",
                        String.format("%.2f", fib382), String.format("%.2f", c2.getLow()));
            }

            log.debug("BUY C2: fifty={} C2.low={} aboveFifty={} ext414Level={} withinExt={} fib382ok={}",
                    String.format("%.2f", fifty), String.format("%.2f", c2.getLow()),
                    aboveFifty, String.format("%.2f", extLevel), withinExt, fib382ok);
            return aboveFifty && withinExt && fib382ok;

        } else {
            // Rule 1 — C2 entirely below 50% midpoint
            boolean belowFifty = c2.getHigh() < fifty;

            // Rule 2 — C2 low must not fall below 1.414 extension BELOW C1 low
            // SELL setup: C2 is consolidating below 50%. It may push below C1 low slightly,
            // but if it goes beyond 1.414 ext downward, price is falling too fast
            // and there is no clean entry.
            // 1.414 level = C1.low - (C1.range × 0.414)
            double  extLevel  = c1.getLow() - ext414;
            boolean withinExt = c2.getLow() >= extLevel;

            // Rule 3 — 38.2% filter (only activates if C2 touches the 38.2% level)
            // 38.2% level measured from BOTTOM of C1 upward
            // = C1.low + (C1.range × 0.382)
            double  fib382 = c1.getLow() + (c1Range * 0.382);
            boolean fib382ok;
            if (c2.getHigh() >= fib382) {
                // C2 touched or crossed 38.2% — must CLOSE below it (rejection candle)
                fib382ok = c2.getClose() < fib382;
                log.debug("SELL 38% touched: fib382={} C2.high={} C2.close={} rejection={}",
                        String.format("%.2f", fib382),
                        String.format("%.2f", c2.getHigh()),
                        String.format("%.2f", c2.getClose()), fib382ok);
            } else {
                // C2 didn't touch 38.2% — no filter needed, pass through
                fib382ok = true;
                log.debug("SELL 38% not touched: fib382={} C2.high={} — no filter applied",
                        String.format("%.2f", fib382), String.format("%.2f", c2.getHigh()));
            }

            log.debug("SELL C2: fifty={} C2.high={} belowFifty={} ext414Level={} withinExt={} fib382ok={}",
                    String.format("%.2f", fifty), String.format("%.2f", c2.getHigh()),
                    belowFifty, String.format("%.2f", extLevel), withinExt, fib382ok);
            return belowFifty && withinExt && fib382ok;
        }
    }

    // =========================================================================
    // Step 4 — Simulate trade
    // =========================================================================

    private BacktestTrade simulateTrade(
            String symbol, LocalDate date, Direction direction,
            Candle c1, Candle c2,
            double entry, double sl, double target, double risk,
            List<Candle> candles) {

        double exitPrice = entry;
        Outcome outcome  = Outcome.EOD_EXIT;
        java.time.LocalDateTime exitDt = null;

        for (Candle c : candles) {
            if (!c.getTimestamp().toLocalTime().isAfter(C2_TIME)) continue;

            // Force exit at or after 3:15 PM — mandatory for both stocks and indexes
            boolean isEod = !c.getTimestamp().toLocalTime().isBefore(EOD_TIME);

            if (direction == Direction.BUY) {
                if (c.getLow() <= sl) {
                    outcome = Outcome.SL_HIT;     exitPrice = sl;     exitDt = c.getTimestamp(); break;
                }
                if (c.getHigh() >= target) {
                    outcome = Outcome.TARGET_HIT; exitPrice = target; exitDt = c.getTimestamp(); break;
                }
            } else {
                if (c.getHigh() >= sl) {
                    outcome = Outcome.SL_HIT;     exitPrice = sl;     exitDt = c.getTimestamp(); break;
                }
                if (c.getLow() <= target) {
                    outcome = Outcome.TARGET_HIT; exitPrice = target; exitDt = c.getTimestamp(); break;
                }
            }

            if (isEod) {
                outcome = Outcome.EOD_EXIT; exitPrice = c.getClose(); exitDt = c.getTimestamp(); break;
            }
        }

        // ── Quantity & Rupee P&L (stocks only) ───────────────────────────────
        // Fixed risk = ₹10,000 per trade
        // Quantity = fixedRiskRupees / riskPoints (how many shares to risk exactly ₹10,000)
        // For indexes: quantity = 0 (lot-based trading, not handled here)
        double fixedRiskRupees = config.getFixedRiskRupees();
        int    quantity        = risk > 0 ? (int) Math.floor(fixedRiskRupees / risk) : 0;

        double pnlPoints  = direction == Direction.BUY ? exitPrice - entry : entry - exitPrice;
        double pnlRupees  = pnlPoints * quantity;
        double pnlPercent = entry > 0 ? (pnlPoints / entry) * 100.0 : 0.0;
        double actualRR   = risk  > 0 ? pnlPoints / risk             : 0.0;

        log.info("  {} {} qty={} riskPts={:.2f} risk₹={:.0f} pnl₹={:.0f} ({})",
                symbol, direction, quantity,
                risk, quantity * risk, pnlRupees, outcome);

        return BacktestTrade.builder()
                .symbol(symbol).tradeDate(date).direction(direction)
                .c1Open(c1.getOpen()).c1High(c1.getHigh()).c1Low(c1.getLow())
                .c1Close(c1.getClose()).c1WickRatio(c1.wickRatio())
                .c2Open(c2.getOpen()).c2High(c2.getHigh()).c2Low(c2.getLow()).c2Close(c2.getClose())
                .entryPrice(entry).stopLoss(sl).target(target)
                .riskPoints(risk).rewardPoints(Math.abs(target - entry))
                .quantity(quantity)
                .riskRupees(quantity * risk)
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
//import java.time.LocalDate;
//import java.time.LocalTime;
//import java.util.List;
//import java.util.Optional;
//
//import org.springframework.stereotype.Service;
//
//import com.trading.algo.config.BacktestConfig;
//import com.trading.algo.dtos.Candle;
//import com.trading.algo.entity.BacktestTrade;
//import com.trading.algo.entity.BacktestTrade.Direction;
//import com.trading.algo.entity.BacktestTrade.Outcome;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//
///**
// * Opening Candle Strategy — corrected rules:
// *
// * STEP 1 — C1 (9:15 candle) qualification:
// *   - Strong directional candle: wick ratio >= minWickRatio (default 0.75)
// *   - Body size >= minCandleBodyPoints
// *   - Bullish C1 → BUY setup, Bearish C1 → SELL setup
// *
// * STEP 2 — C2 (9:30 candle) confirmation:
// *   BULLISH (BUY):
// *     - C2.low  > 50% of C1  → entire C2 stays ABOVE midpoint (no deep pullback)
// *     - C2.range <= 1.41% of C1.low → C2 is a tight consolidation candle
// *   BEARISH (SELL):
// *     - C2.high < 50% of C1  → entire C2 stays BELOW midpoint (no deep recovery)
// *     - C2.range <= 1.41% of C1.high → C2 is a tight consolidation candle
// *
// * STEP 3 — Entry at C2 close:
// *   BUY  : SL = C2.low  - 0.015%,  Target = Entry + risk × 2.5
// *   SELL : SL = C2.high + 0.015%,  Target = Entry - risk × 2.5
// *
// * STEP 4 — Simulate on subsequent candles until TARGET, SL, or EOD
// */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class OpeningCandleStrategyService {
//
//    private static final LocalTime C1_TIME  = LocalTime.of(9, 15);
//    private static final LocalTime C2_TIME  = LocalTime.of(9, 30);
//    private static final LocalTime EOD_TIME = LocalTime.of(15, 15); // force close at 3:15 PM
//
//    private final BacktestConfig config;
//    
////    public OpeningCandleStrategyService(BacktestConfig config) {
////    	this.config=config;
////    }
//
//    public Optional<BacktestTrade> evaluate(String symbol, LocalDate date, List<Candle> candles) {
//        if (candles == null || candles.size() < 3) {
//            return Optional.empty();
//        }
//
//        Candle c1 = findCandle(candles, C1_TIME);
//        Candle c2 = findCandle(candles, C2_TIME);
//
//        if (c1 == null || c2 == null) {
//            log.debug("{} {} — C1 or C2 not found", symbol, date);
//            return Optional.empty();
//        }
//
//        // ── STEP 1: C1 must be a strong directional candle ───────────────────
//        if (!isStrongCandle(c1)) {
//            log.debug("{} {} — C1 weak: wickRatio={} body={}", symbol, date,
//                    String.format("%.3f", c1.wickRatio()),
//                    String.format("%.2f", c1.body()));
//            return Optional.empty();
//        }
//
//        Direction direction = c1.isBullish() ? Direction.BUY : Direction.SELL;
//
//        // ── STEP 2: C2 confirmation ───────────────────────────────────────────
//        if (!isValidC2(c1, c2, direction)) {
//            log.debug("{} {} — C2 did not confirm {} setup", symbol, date, direction);
//            return Optional.empty();
//        }
//
//        // ── STEP 3: Trade levels — SL from C2 high/low ───────────────────────
//        double slMarginFactor = config.getSlMarginPercent() / 100.0;  // 0.45%
//        double entry, sl, risk, target;
//
//        if (direction == Direction.BUY) {
//            entry  = c2.getClose();
//            sl     = c2.getLow() * (1 - slMarginFactor);   // C2 low - 0.015%
//            risk   = entry - sl;
//            target = entry + (risk * config.getTargetRR());
//        } else {
//            entry  = c2.getClose();
//            sl     = c2.getHigh() * (1 + slMarginFactor);  // C2 high + 0.015%
//            risk   = sl - entry;
//            target = entry - (risk * config.getTargetRR());
//        }
//
//        if (risk <= 0) {
//            log.debug("{} {} — invalid risk {}", symbol, date, risk);
//            return Optional.empty();
//        }
//
//        log.info("{} {} — {} | Entry={} SL={} Target={} Risk={}pts C2range={}%",
//                symbol, date, direction,
//                String.format("%.2f", entry),
//                String.format("%.2f", sl),
//                String.format("%.2f", target),
//                String.format("%.2f", risk),
//                String.format("%.3f", (c2.range() / c2.getHigh()) * 100));
//
//        // ── STEP 4: Simulate ──────────────────────────────────────────────────
//        BacktestTrade trade = simulateTrade(symbol, date, direction,
//                c1, c2, entry, sl, target, risk, candles);
//
//        return Optional.of(trade);
//    }
//
//    // =========================================================================
//    // Step 1 — C1 strong candle check
//    // =========================================================================
//
//    private boolean isStrongCandle(Candle c) {
//        return c.body()      >= config.getMinCandleBodyPoints()
//            && c.wickRatio() >= config.getMinWickRatio();
//    }
//
//    // =========================================================================
//    // Step 2 — C2 confirmation
//    // =========================================================================
//
//    /**
//     * BULLISH setup (BUY):
//     *   Rule 1 — C2 entirely ABOVE 50% of C1:
//     *     C2.low > (C1.high + C1.low) / 2
//     *   Rule 2 — C2 must NOT break below the 1.414 Fibonacci extension:
//     *     C2.low >= C1.low - (C1.range x 0.414)
//     *
//     * BEARISH setup (SELL):
//     *   Rule 1 — C2 entirely BELOW 50% of C1:
//     *     C2.high < (C1.high + C1.low) / 2
//     *   Rule 2 — C2 must NOT break above the 1.414 Fibonacci extension:
//     *     C2.high <= C1.high + (C1.range x 0.414)
//     *
//     * Verified: C1.high=23221.35 C1.low=23062.60 -> ext414=23287.07 matches chart exactly.
//     */
//    private boolean isValidC2(Candle c1, Candle c2, Direction direction) {
//        double fifty   = c1.fiftyPercent();
//        double c1Range = c1.range();
//        double ext414  = c1Range * 0.414;
//
//        if (direction == Direction.BUY) {
//            // Rule 1 — C2 entirely above 50% midpoint
//            boolean aboveFifty = c2.getLow() > fifty;
//
//            // Rule 2 — C2 high must not exceed 1.414 extension ABOVE C1 high
//            // BUY setup: C2 is consolidating above 50%. It may push above C1 high slightly,
//            // but if it goes beyond 1.414 ext upward, price is running away too fast
//            // and there is no clean pullback entry.
//            // 1.414 level = C1.high + (C1.range × 0.414)
//            double  extLevel  = c1.getHigh() + ext414;
//            boolean withinExt = c2.getHigh() <= extLevel;
//
//            // Rule 3 — 38.2% filter (only activates if C2 touches the 38.2% level)
//            // 38.2% level measured from TOP of C1 downward
//            // = C1.high - (C1.range × 0.382)
//            double  fib382 = c1.getHigh() - (c1Range * 0.382);
//            boolean fib382ok;
//            if (c2.getLow() <= fib382) {
//                // C2 touched or crossed 38.2% — must CLOSE above it (rejection candle)
//                fib382ok = c2.getClose() > fib382;
//                log.debug("BUY 38% touched: fib382={} C2.low={} C2.close={} rejection={}",
//                        String.format("%.2f", fib382),
//                        String.format("%.2f", c2.getLow()),
//                        String.format("%.2f", c2.getClose()), fib382ok);
//            } else {
//                // C2 didn't touch 38.2% — no filter needed, pass through
//                fib382ok = true;
//                log.debug("BUY 38% not touched: fib382={} C2.low={} — no filter applied",
//                        String.format("%.2f", fib382), String.format("%.2f", c2.getLow()));
//            }
//
//            log.debug("BUY C2: fifty={} C2.low={} aboveFifty={} ext414Level={} withinExt={} fib382ok={}",
//                    String.format("%.2f", fifty), String.format("%.2f", c2.getLow()),
//                    aboveFifty, String.format("%.2f", extLevel), withinExt, fib382ok);
//            return aboveFifty && withinExt && fib382ok;
//
//        } else {
//            // Rule 1 — C2 entirely below 50% midpoint
//            boolean belowFifty = c2.getHigh() < fifty;
//
//            // Rule 2 — C2 low must not fall below 1.414 extension BELOW C1 low
//            // SELL setup: C2 is consolidating below 50%. It may push below C1 low slightly,
//            // but if it goes beyond 1.414 ext downward, price is falling too fast
//            // and there is no clean entry.
//            // 1.414 level = C1.low - (C1.range × 0.414)
//            double  extLevel  = c1.getLow() - ext414;
//            boolean withinExt = c2.getLow() >= extLevel;
//
//            // Rule 3 — 38.2% filter (only activates if C2 touches the 38.2% level)
//            // 38.2% level measured from BOTTOM of C1 upward
//            // = C1.low + (C1.range × 0.382)
//            double  fib382 = c1.getLow() + (c1Range * 0.382);
//            boolean fib382ok;
//            if (c2.getHigh() >= fib382) {
//                // C2 touched or crossed 38.2% — must CLOSE below it (rejection candle)
//                fib382ok = c2.getClose() < fib382;
//                log.debug("SELL 38% touched: fib382={} C2.high={} C2.close={} rejection={}",
//                        String.format("%.2f", fib382),
//                        String.format("%.2f", c2.getHigh()),
//                        String.format("%.2f", c2.getClose()), fib382ok);
//            } else {
//                // C2 didn't touch 38.2% — no filter needed, pass through
//                fib382ok = true;
//                log.debug("SELL 38% not touched: fib382={} C2.high={} — no filter applied",
//                        String.format("%.2f", fib382), String.format("%.2f", c2.getHigh()));
//            }
//
//            log.debug("SELL C2: fifty={} C2.high={} belowFifty={} ext414Level={} withinExt={} fib382ok={}",
//                    String.format("%.2f", fifty), String.format("%.2f", c2.getHigh()),
//                    belowFifty, String.format("%.2f", extLevel), withinExt, fib382ok);
//            return belowFifty && withinExt && fib382ok;
//        }
//    }
//
//    // =========================================================================
//    // Step 4 — Simulate trade
//    // =========================================================================
//
//    private BacktestTrade simulateTrade(
//            String symbol, LocalDate date, Direction direction,
//            Candle c1, Candle c2,
//            double entry, double sl, double target, double risk,
//            List<Candle> candles) {
//
//        double exitPrice = entry;
//        Outcome outcome  = Outcome.EOD_EXIT;
//        java.time.LocalDateTime exitDt = null;
//
//        for (Candle c : candles) {
//            if (!c.getTimestamp().toLocalTime().isAfter(C2_TIME)) continue;
//
//            // Force exit at or after 3:15 PM — mandatory for both stocks and indexes
//            boolean isEod = !c.getTimestamp().toLocalTime().isBefore(EOD_TIME);
//
//            if (direction == Direction.BUY) {
//                if (c.getLow() <= sl) {
//                    outcome = Outcome.SL_HIT;     exitPrice = sl;     exitDt = c.getTimestamp(); break;
//                }
//                if (c.getHigh() >= target) {
//                    outcome = Outcome.TARGET_HIT; exitPrice = target; exitDt = c.getTimestamp(); break;
//                }
//            } else {
//                if (c.getHigh() >= sl) {
//                    outcome = Outcome.SL_HIT;     exitPrice = sl;     exitDt = c.getTimestamp(); break;
//                }
//                if (c.getLow() <= target) {
//                    outcome = Outcome.TARGET_HIT; exitPrice = target; exitDt = c.getTimestamp(); break;
//                }
//            }
//
//            if (isEod) {
//                outcome = Outcome.EOD_EXIT; exitPrice = c.getClose(); exitDt = c.getTimestamp(); break;
//            }
//        }
//
//        // ── Quantity & Rupee P&L (stocks only) ───────────────────────────────
//        // Fixed risk = ₹10,000 per trade
//        // Quantity = fixedRiskRupees / riskPoints (how many shares to risk exactly ₹10,000)
//        // For indexes: quantity = 0 (lot-based trading, not handled here)
//        double fixedRiskRupees = config.getFixedRiskRupees();
//        int    quantity        = risk > 0 ? (int) Math.floor(fixedRiskRupees / risk) : 0;
//
//        double pnlPoints  = direction == Direction.BUY ? exitPrice - entry : entry - exitPrice;
//        double pnlRupees  = pnlPoints * quantity;
//        double pnlPercent = entry > 0 ? (pnlPoints / entry) * 100.0 : 0.0;
//        double actualRR   = risk  > 0 ? pnlPoints / risk             : 0.0;
//
//        log.info("  {} {} qty={} riskPts={:.2f} risk₹={:.0f} pnl₹={:.0f} ({})",
//                symbol, direction, quantity,
//                risk, quantity * risk, pnlRupees, outcome);
//
//        return BacktestTrade.builder()
//                .symbol(symbol).tradeDate(date).direction(direction)
//                .c1Open(c1.getOpen()).c1High(c1.getHigh()).c1Low(c1.getLow())
//                .c1Close(c1.getClose()).c1WickRatio(c1.wickRatio())
//                .c2Open(c2.getOpen()).c2High(c2.getHigh()).c2Low(c2.getLow()).c2Close(c2.getClose())
//                .entryPrice(entry).stopLoss(sl).target(target)
//                .riskPoints(risk).rewardPoints(Math.abs(target - entry))
//                .quantity(quantity)
//                .riskRupees(quantity * risk)
//                .pnlRupees(pnlRupees)
//                .outcome(outcome).exitPrice(exitPrice)
//                .pnlPoints(pnlPoints).pnlPercent(pnlPercent).actualRR(actualRR)
//                .exitCandleTime(exitDt)
//                .createdAt(java.time.LocalDateTime.now())
//                .build();
//    }
//
//    private Candle findCandle(List<Candle> candles, LocalTime time) {
//        return candles.stream()
//                .filter(c -> c.getTimestamp().toLocalTime().equals(time))
//                .findFirst().orElse(null);
//    }
//}