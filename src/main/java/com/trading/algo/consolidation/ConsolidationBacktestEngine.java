package com.trading.algo.consolidation;

import com.trading.algo.consolidation.ConsolidationTradeRecord.Direction;
import com.trading.algo.consolidation.ConsolidationTradeRecord.ExitReason;
import com.trading.algo.consolidation.ConsolidationTradeRecord.Timeframe;
import com.trading.algo.dtos.Candle;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Backtest engine for the Consolidation Breakout strategy.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT THIS DOES
 * ─────────────────────────────────────────────────────────────────────────────
 * For each trading day in the date range:
 *   1. Fetch all candles for that day (15-min or 5-min)
 *   2. Walk forward candle by candle starting from index = lookback
 *   3. At each candle i:
 *        - window = candles[i-lookback .. i-1]  (the N candles before current)
 *        - zoneHigh = max(high) of window
 *        - zoneLow  = min(low)  of window
 *        - rangeWidth = (zoneHigh - zoneLow) / midpoint × 100
 *        - If rangeWidth > maxRangePct → not a consolidation, skip
 *        - If candle[i].close > zoneHigh → BULLISH breakout
 *        - If candle[i].close < zoneLow  → BEARISH breakout
 *   4. On breakout:
 *        - Entry  = breakout candle close
 *        - SL     = opposite side of zone (zoneLow for BULLISH, zoneHigh for BEARISH)
 *        - Risk   = |entry - SL|
 *        - Target = entry ± (risk × targetRR)
 *   5. Simulate forward candle by candle:
 *        - SL hit   → EXIT (SL_HIT),     pnlR = -1.0
 *        - Target hit → EXIT (TARGET_HIT), pnlR = +targetRR
 *        - 3:15 PM reached → EXIT (EOD_EXIT), pnlR = (exitClose - entry) / risk
 *   6. One trade per zone per day — same zone cannot trigger twice
 *      (keyed by zoneHigh + zoneLow rounded to 2dp)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * VOLUME NOTE
 * ─────────────────────────────────────────────────────────────────────────────
 * Volume confirmation is NOT applied — we are using NSE index candles whose
 * volume is synthetic (sum of constituent stock volumes, not futures volume).
 * See ConsolidationBreakoutService for full explanation.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EOD EXIT
 * ─────────────────────────────────────────────────────────────────────────────
 * Any open trade at 3:15 PM is closed at that candle's close price.
 * EOD exits are recorded separately and excluded from win rate calculation
 * (they are partial outcomes — not clean wins or losses).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsolidationBacktestEngine {

    // EOD exit time — close all open trades at or after this time
    private static final LocalTime EOD_EXIT_TIME = LocalTime.of(15, 15);

    private final UpstoxHistoricalCandleService candleService;

    // ── Entry point ───────────────────────────────────────────────────────────

    public ConsolidationBacktestResult run(
            String label,
            String instrumentKey,
            LocalDate fromDate,
            LocalDate toDate,
            boolean is15m,
            double maxRangePct,
            double targetRR,
            double confirmPct,
            double closeDistanceThreshold) {   // 0.0 = no filter, 40.0 for Nifty, 70.0 for Bank Nifty

        int      lookback  = is15m ? 4 : 6;
        String   tf        = is15m ? "15m" : "5m";
        Timeframe timeframe = is15m ? Timeframe.TF_15M : Timeframe.TF_5M;

        log.info("Consolidation backtest START | {} [{}] from={} to={} maxRange={}% targetRR={} confirmPct={}% closeDistThreshold={}",
                label, tf, fromDate, toDate, maxRangePct, targetRR, confirmPct * 100, closeDistanceThreshold);

        List<ConsolidationTradeRecord> allTrades = new ArrayList<>();

        // Walk each trading day individually — same pattern as IndexBacktestService
        LocalDate day = fromDate;
        while (!day.isAfter(toDate)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                try {
                    List<Candle> candles = is15m
                            ? candleService.fetchDayCandles(instrumentKey, day)
                            : candleService.fetch5mDayCandles(instrumentKey, day);

                    if (candles != null && candles.size() >= lookback + 1) {
                        // Fetch previous trading day's close for PDC filter.
                        // PDC acts as a magnet — if it sits between entry and target,
                        // price is likely to stall there before reaching the target.
                        double prevDayClose = fetchPrevDayClose(instrumentKey, day);

                        List<ConsolidationTradeRecord> dayTrades =
                                backtestDay(label, timeframe, day, candles, lookback, maxRangePct, targetRR, confirmPct, closeDistanceThreshold, prevDayClose);
                        allTrades.addAll(dayTrades);
                        log.info("[{}][{}] {} — {} trades found (PDC={})", tf, label, day, dayTrades.size(), prevDayClose);
                    } else {
                        log.debug("[{}][{}] {} — not enough candles ({})", tf, label, day,
                                candles == null ? 0 : candles.size());
                    }
                } catch (Exception e) {
                    log.error("[{}][{}] Error on {}: {}", tf, label, day, e.getMessage());
                }
            }
            day = day.plusDays(1);
        }

        log.info("Consolidation backtest COMPLETE | {} [{}] — {} total trades", label, tf, allTrades.size());
        return aggregate(label, tf, fromDate, toDate, maxRangePct, lookback, targetRR, confirmPct, allTrades);
    }

    // ── Fetch previous day close ─────────────────────────────────────────────

    /**
     * Fetches the previous trading day's closing price by getting the last candle
     * of the previous weekday. Returns 0.0 if unavailable (filter is skipped when PDC = 0).
     */
    private double fetchPrevDayClose(String instrumentKey, LocalDate date) {
        try {
            LocalDate prevDay = date.minusDays(1);
            // Skip weekends
            while (prevDay.getDayOfWeek() == DayOfWeek.SATURDAY ||
                   prevDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
                prevDay = prevDay.minusDays(1);
            }
            List<Candle> prevCandles = candleService.fetchDayCandles(instrumentKey, prevDay);
            if (prevCandles == null || prevCandles.isEmpty()) return 0.0;
            return prevCandles.get(prevCandles.size() - 1).getClose();
        } catch (Exception e) {
            log.warn("Could not fetch PDC for {} on {}: {}", instrumentKey, date, e.getMessage());
            return 0.0;
        }
    }

    // ── Per-day backtest ──────────────────────────────────────────────────────

    private List<ConsolidationTradeRecord> backtestDay(
            String label, Timeframe timeframe, LocalDate date,
            List<Candle> candles, int lookback, double maxRangePct, double targetRR, double confirmPct,
            double closeDistanceThreshold, double prevDayClose) {

        List<ConsolidationTradeRecord> trades = new ArrayList<>();

        // Dedup 1: one trade per zone (keyed by zoneHigh:zoneLow)
        Set<String> tradedZones = new HashSet<>();

        // Dedup 2: consecutive same-direction breakout filter.
        //
        // Problem: when price is trending strongly after a breakout, the next candle
        // can also look like a breakout from a slightly shifted zone — same direction,
        // same move, just chasing. Example from data:
        //   12:30 BEARISH TARGET_HIT (zone 24214.65–24166.80)
        //   12:45 BEARISH EOD_EXIT   (zone 24194.35–24143.15) ← same move, different zone key
        //
        // Rule: if the immediately previous candle already triggered a breakout in the
        // same direction, skip this signal. The move is already in progress — no new entry.
        //
        // Reset condition: direction resets to null when the previous candle did NOT
        // trigger a breakout (price was inside a zone or no consolidation found),
        // meaning the market has paused and a fresh breakout is valid again.
        Direction lastBreakoutDirection = null;

        for (int i = lookback; i < candles.size(); i++) {
            Candle current = candles.get(i);

            // Skip candles at or after EOD exit time — no new entries
            if (!current.getTimestamp().toLocalTime().isBefore(EOD_EXIT_TIME)) break;

            List<Candle> window = candles.subList(i - lookback, i);

            // ── Measure consolidation zone ────────────────────────────────────
            double zoneHigh = window.stream().mapToDouble(Candle::getHigh).max().orElse(0);
            double zoneLow  = window.stream().mapToDouble(Candle::getLow).min().orElse(0);
            double midpoint = (zoneHigh + zoneLow) / 2.0;
            if (midpoint == 0) continue;

            double rangeWidth = (zoneHigh - zoneLow) / midpoint * 100.0;
            if (rangeWidth > maxRangePct) continue;

            // ── Volume check — COMMENTED OUT (index candles, not futures) ─────
            // NSE index volume is synthetic — not meaningful for breakout confirmation.
            // Re-enable when switching to futures instrument keys.
            //
            // double avgVol = window.stream().mapToLong(Candle::getVolume).average().orElse(0);
            // double volRatio = avgVol > 0 ? current.getVolume() / avgVol : 0;
            // if (volRatio < 1.5) continue;

            // ── Confirmation filter (Option 1 + Option 3 combined) ────────────
            //
            // Option 1 — % of zone width beyond boundary:
            //   confirmation = zoneWidth * confirmPct
            //   BULLISH: close must be >= zoneHigh + confirmation
            //   BEARISH: close must be <= zoneLow  - confirmation
            //   Filters marginal closes that barely crossed the boundary.
            //
            // Option 3 — body % outside zone:
            //   At least 50% of the candle body must be outside the zone.
            //   BULLISH: (close - zoneHigh) / body >= 0.5
            //   BEARISH: (zoneLow - close)  / body >= 0.5
            //   Filters long-wick fake breakouts where price spiked through
            //   but the body closed back near the boundary.
            //
            // When confirmPct = 0.0, both filters are effectively disabled
            // (confirmation = 0, body check = 0/body = 0 which always passes).
            double zoneWidth     = zoneHigh - zoneLow;
            double confirmation  = zoneWidth * confirmPct;
            double body          = current.body();

            // ── Check breakout direction ──────────────────────────────────────
            boolean bullish = current.getClose() >= zoneHigh + confirmation
                    && (body == 0 || (current.getClose() - zoneHigh) / body >= 0.5);
            boolean bearish = current.getClose() <= zoneLow - confirmation
                    && (body == 0 || (zoneLow - current.getClose()) / body >= 0.5);
            if (!bullish && !bearish) {
                // No breakout this candle — reset the consecutive direction tracker.
                // Price is back inside a zone or no consolidation found, so the next
                // genuine breakout in any direction is valid.
                lastBreakoutDirection = null;
                continue;
            }

            Direction dir = bullish ? Direction.BULLISH : Direction.BEARISH;

            // ── Consecutive same-direction filter ─────────────────────────────
            // If the previous candle already fired a breakout in the same direction,
            // this is just the same move continuing — skip it.
            if (dir == lastBreakoutDirection) {
                log.debug("[{}] {} — skipping consecutive {} breakout (same direction as previous candle)",
                        label, current.getTimestamp(), dir);
                // Do NOT reset lastBreakoutDirection here — keep blocking until
                // a non-breakout candle resets it.
                continue;
            }

            // ── Dedup — one trade per zone per day ────────────────────────────
            String zoneKey = String.format("%.2f:%.2f", zoneHigh, zoneLow);
            if (tradedZones.contains(zoneKey)) continue;
            tradedZones.add(zoneKey);

            // Record this candle's direction so the next candle can check it
            lastBreakoutDirection = dir;
            double    entry  = current.getClose();
            double    sl     = bullish ? zoneLow : zoneHigh;
            double    risk   = Math.abs(entry - sl);
            if (risk == 0) continue;

            double target = bullish
                    ? entry + risk * targetRR
                    : entry - risk * targetRR;

            // ── PDC (Previous Day Close) filter ────────────────────────────────
            // PDC acts as a price magnet — price tends to stall or reverse at the
            // previous day's close level. If PDC sits between entry and target,
            // the move is likely to get blocked before reaching the target.
            //
            // BULLISH: entry < PDC < target → PDC is in the path → skip
            // BEARISH: target < PDC < entry → PDC is in the path → skip
            //
            // prevDayClose = 0.0 means it was unavailable — filter is skipped.
            if (prevDayClose > 0) {
                boolean pdcInPath = bullish
                        ? prevDayClose > entry && prevDayClose < target
                        : prevDayClose < entry && prevDayClose > target;

                if (pdcInPath) {
                    log.debug("[{}] {} — PDC filter: {} PDC={} is between entry={} and target={} — skipping",
                            label, current.getTimestamp(), dir, prevDayClose, entry, target);
                    continue;
                }

                // ── Close distance threshold filter ───────────────────────────
                // Skip trade if current close is too close to previous day close.
                // This prevents trades when price is near PDC (price magnet effect).
                // closeDistanceThreshold: 0.0 = disabled, 40.0 for Nifty, 70.0 for Bank Nifty
                if (closeDistanceThreshold > 0) {
                    double closeDistance = Math.abs(entry - prevDayClose);
                    if (closeDistance < closeDistanceThreshold) {
                        log.debug("[{}] {} — Close distance filter: {} close={} is only {:.2f} pts from PDC={} (threshold={}) — skipping",
                                label, current.getTimestamp(), dir, entry, closeDistance, prevDayClose, closeDistanceThreshold);
                        continue;
                    }
                }
            }

            // ── Simulate forward from candle i+1 ─────────────────────────────
            ConsolidationTradeRecord trade = simulate(
                    label, timeframe, date, dir,
                    current, zoneHigh, zoneLow, rangeWidth,
                    entry, sl, target, risk, targetRR, prevDayClose,
                    candles, i);

            trades.add(trade);
        }

        return trades;
    }

    // ── Walk-forward simulation ───────────────────────────────────────────────

    private ConsolidationTradeRecord simulate(
            String label, Timeframe timeframe, LocalDate date, Direction dir,
            Candle entryCandle, double zoneHigh, double zoneLow, double rangeWidth,
            double entry, double sl, double target, double risk, double targetRR,
            double prevDayClose, List<Candle> candles, int entryIdx) {

        ExitReason    exitReason = ExitReason.EOD_EXIT;
        double        exitPrice  = entry;  // default: EOD exit at entry (flat)
        LocalTime     eodTime    = null;

        for (int j = entryIdx + 1; j < candles.size(); j++) {
            Candle c = candles.get(j);

            // EOD exit — close at this candle's close price
            if (!c.getTimestamp().toLocalTime().isBefore(EOD_EXIT_TIME)) {
                exitPrice  = c.getClose();
                exitReason = ExitReason.EOD_EXIT;
                eodTime    = c.getTimestamp().toLocalTime();
                break;
            }

            if (dir == Direction.BULLISH) {
                if (c.getLow() <= sl) {
                    exitPrice = sl; exitReason = ExitReason.SL_HIT; break;
                }
                if (c.getHigh() >= target) {
                    exitPrice = target; exitReason = ExitReason.TARGET_HIT; break;
                }
            } else {
                if (c.getHigh() >= sl) {
                    exitPrice = sl; exitReason = ExitReason.SL_HIT; break;
                }
                if (c.getLow() <= target) {
                    exitPrice = target; exitReason = ExitReason.TARGET_HIT; break;
                }
            }
        }

        double rawPnl = dir == Direction.BULLISH
                ? exitPrice - entry
                : entry - exitPrice;

        double pnlR = risk > 0 ? rawPnl / risk : 0;

        return ConsolidationTradeRecord.builder()
                .label(label)
                .timeframe(timeframe)
                .direction(dir)
                .tradeDate(date)
                .breakoutCandleTime(entryCandle.getTimestamp())
                .zoneHigh(zoneHigh)
                .zoneLow(zoneLow)
                .zoneRangePct(rangeWidth)
                .entry(entry)
                .stopLoss(sl)
                .target(target)
                .riskPoints(risk)
                .prevDayClose(prevDayClose)
                .pdcFiltered(false)
                .exitReason(exitReason)
                .exitPrice(exitPrice)
                .exitTime(exitReason == ExitReason.EOD_EXIT && eodTime != null
                        ? date.atTime(eodTime) : null)
                .pnlPoints(rawPnl)
                .pnlR(round(pnlR))
                .build();
    }

    // ── Aggregation ───────────────────────────────────────────────────────────

    private ConsolidationBacktestResult aggregate(
            String label, String tf,
            LocalDate fromDate, LocalDate toDate,
            double maxRangePct, int lookback, double targetRR, double confirmPct,
            List<ConsolidationTradeRecord> trades) {

        int wins = 0, losses = 0, eodExits = 0;
        double totalR = 0, grossProfit = 0, grossLoss = 0;
        double maxWinR = Double.MIN_VALUE, maxLossR = Double.MAX_VALUE;
        double sumWinR = 0, sumLossR = 0;
        int consecWins = 0, consecLosses = 0, maxCW = 0, maxCL = 0;
        double equity = 0, peak = 0, maxDrawdown = 0;

        for (ConsolidationTradeRecord t : trades) {
            double r = t.getPnlR();

            // Only count clean wins/losses in equity curve — exclude EOD exits
            if (t.getExitReason() != ExitReason.EOD_EXIT) {
                totalR += r;
                equity += r;
                if (equity > peak) peak = equity;
                double dd = peak - equity;
                if (dd > maxDrawdown) maxDrawdown = dd;
            }

            switch (t.getExitReason()) {
                case TARGET_HIT -> {
                    wins++; grossProfit += r; sumWinR += r;
                    if (r > maxWinR) maxWinR = r;
                    consecWins++; consecLosses = 0;
                    if (consecWins > maxCW) maxCW = consecWins;
                }
                case SL_HIT -> {
                    losses++; grossLoss += Math.abs(r); sumLossR += r;
                    if (r < maxLossR) maxLossR = r;
                    consecLosses++; consecWins = 0;
                    if (consecLosses > maxCL) maxCL = consecLosses;
                }
                case EOD_EXIT -> eodExits++;
            }
        }

        int closed = wins + losses;
        double winRate      = closed > 0 ? (double) wins / closed * 100.0 : 0;
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : grossProfit;
        double avgWinR      = wins   > 0 ? sumWinR  / wins   : 0;
        double avgLossR     = losses > 0 ? sumLossR / losses : 0;
        double avgPnlR      = closed > 0 ? totalR   / closed : 0;

        // PDC filtered trades are not in the trades list (they were skipped via continue)
        // so pdcFilteredTrades is tracked separately — not applicable here since skipped
        // trades never reach simulate(). The count is 0 in the result but the log shows them.
        return ConsolidationBacktestResult.builder()
                .label(label)
                .timeframe(tf)
                .fromDate(fromDate)
                .toDate(toDate)
                .maxRangePct(maxRangePct)
                .lookbackCandles(lookback)
                .targetRR(targetRR)
                .confirmPct(confirmPct)
                .totalTrades(trades.size())
                .wins(wins)
                .losses(losses)
                .eodExits(eodExits)
                .pdcFilteredTrades(0) // skipped trades don't appear in list; see logs for count
                .winRate(round(winRate))
                .totalPnlR(round(totalR))
                .avgPnlR(round(avgPnlR))
                .profitFactor(round(profitFactor))
                .maxDrawdownR(round(maxDrawdown))
                .avgWinR(round(avgWinR))
                .avgLossR(round(avgLossR))
                .largestWinR(maxWinR == Double.MIN_VALUE ? 0 : round(maxWinR))
                .largestLossR(maxLossR == Double.MAX_VALUE ? 0 : round(maxLossR))
                .maxConsecWins(maxCW)
                .maxConsecLosses(maxCL)
                .trades(trades)
                .build();
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
