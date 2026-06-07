package com.trading.algo.delta.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.trading.algo.delta.model.BacktestRequest;
import com.trading.algo.delta.model.BacktestResult;
import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.TradeRecord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Backtest engine.
 *
 * Strategy:
 * ─────────────────────────────────────────────────────────────────────────────
 * SIGNAL  : 15-min candle closes BELOW previous-day low  → SHORT
 *           15-min candle closes ABOVE previous-day high → LONG
 *
 * ENTRY   : Close price of the signal candle (market-on-close approximation)
 *
 * STOP    : For SHORT: high of the 2nd candle PRIOR to the signal candle
 *           × (1 + slMarginPct/100)
 *           For LONG : low  of the 2nd candle PRIOR to the signal candle
 *           × (1 - slMarginPct/100)
 *
 * RISK    : |entry − adjustedSL|
 *
 * TARGETS :
 *   Partial (1:partialExitRR, default 1:2)  → close 50 % of position
 *                                             + trail SL to entry (breakeven)
 *   Full    (1:riskRewardRatio, default 1:3) → close remaining 50 %
 *
 * EXIT SCENARIOS:
 *   A) Price hits full target before SL     → WIN   (+3R weighted)
 *   B) Partial hit, then full target        → WIN   (weighted > +2R)
 *   C) Partial hit, then SL (breakeven)     → PARTIAL WIN (+R for partial, 0 for SL leg)
 *   D) SL hit before any partial            → LOSS  (-1R)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * NOTE: Each day can produce at most ONE trade per direction to avoid stacking
 * signals from the same PDL/PDH breach on consecutive candles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private static final MathContext MC   = MathContext.DECIMAL64;
    private static final BigDecimal  HUNDRED = BigDecimal.valueOf(100);
    private static final int         CANDLE_15M_SEC = 900;

    private final DeltaApiService deltaApiService;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    public BacktestResult run(BacktestRequest req) {
        log.info("Starting backtest: symbol={} from={} to={}", req.getSymbol(), req.getFromDate(), req.getToDate());

        // 1. Fetch all 15-min candles for the full window (+ 1 day padding for PDL)
        long windowStart = req.getFromDate().minusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long windowEnd   = req.getToDate().plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toEpochSecond();

        List<Candle> allCandles = deltaApiService.get15mCandles(req.getSymbol(), windowStart, windowEnd);
        log.info("Fetched {} 15m candles for {}", allCandles.size(), req.getSymbol());

        if (allCandles.size() < 3) {
            log.warn("Not enough candles to backtest.");
            return emptyResult(req);
        }

        // 2. Build daily-high/low map from the same candle data (avoids extra API calls)
        Map<String, DayHighLow> dailyMap = buildDailyMap(allCandles);

        // 3. Iterate candles, find signals, simulate trades
        List<TradeRecord> trades = new ArrayList<>();

        // Track which days we've already opened a SHORT / LONG (one per day per direction)
        Set<String> shortDaysSeen = new HashSet<>();
        Set<String> longDaysSeen  = new HashSet<>();

        for (int i = 2; i < allCandles.size(); i++) {
            Candle signal = allCandles.get(i);

            // Only evaluate candles in the requested date window
            String candleDay = dayKey(signal);
            if (signal.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate()
                    .isBefore(req.getFromDate())) continue;
            if (signal.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate()
                    .isAfter(req.getToDate())) break;

            // Retrieve previous-day levels for this candle's day
            String prevDayKey = prevDayKey(signal);
            DayHighLow pdl = dailyMap.get(prevDayKey);
            if (pdl == null) continue;   // no PDL data for this day

            Candle refCandle = allCandles.get(i - 2); // 2nd candle prior

            // ── BEARISH BREAKDOWN (SHORT) ──────────────────────────────────
            if (!shortDaysSeen.contains(candleDay)
                    && signal.getClose().compareTo(pdl.low) < 0) {

                TradeRecord trade = buildShortTrade(req, signal, refCandle, allCandles, i);
                trades.add(trade);
                shortDaysSeen.add(candleDay);
                log.debug("SHORT signal at {} | entry={} | sl={} | target={}",
                        signal.getCloseTime(), trade.getEntry(),
                        trade.getAdjustedSl(), trade.getFullTarget());
            }

            // ── BULLISH BREAKOUT (LONG) ────────────────────────────────────
            if (!longDaysSeen.contains(candleDay)
                    && signal.getClose().compareTo(pdl.high) > 0) {

                TradeRecord trade = buildLongTrade(req, signal, refCandle, allCandles, i);
                trades.add(trade);
                longDaysSeen.add(candleDay);
                log.debug("LONG signal at {} | entry={} | sl={} | target={}",
                        signal.getCloseTime(), trade.getEntry(),
                        trade.getAdjustedSl(), trade.getFullTarget());
            }
        }

        log.info("Backtest complete: {} trades found for {}", trades.size(), req.getSymbol());
        return aggregate(req, trades);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trade builders
    // ─────────────────────────────────────────────────────────────────────────

    private TradeRecord buildShortTrade(BacktestRequest req,
                                        Candle signal, Candle refCandle,
                                        List<Candle> allCandles, int signalIdx) {

        BigDecimal entry = signal.getClose();

        // SL = high of 2nd-prior candle + margin%
        BigDecimal rawSl      = refCandle.getHigh();
        BigDecimal margin     = rawSl.multiply(bd(req.getSlMarginPct() / 100.0), MC);
        BigDecimal adjustedSl = rawSl.add(margin, MC);

        BigDecimal risk         = adjustedSl.subtract(entry).abs(MC);
        BigDecimal partialTarget = entry.subtract(risk.multiply(bd(req.getPartialExitRR()), MC), MC);
        BigDecimal fullTarget    = entry.subtract(risk.multiply(bd(req.getRiskRewardRatio()), MC), MC);
        BigDecimal trailedSl     = entry;  // breakeven after partial

        return simulate(req, signal, refCandle, allCandles, signalIdx,
                TradeRecord.Direction.SHORT,
                entry, rawSl, adjustedSl, risk, partialTarget, fullTarget, trailedSl);
    }

    private TradeRecord buildLongTrade(BacktestRequest req,
                                       Candle signal, Candle refCandle,
                                       List<Candle> allCandles, int signalIdx) {

        BigDecimal entry = signal.getClose();

        // SL = low of 2nd-prior candle - margin%
        BigDecimal rawSl      = refCandle.getLow();
        BigDecimal margin     = rawSl.multiply(bd(req.getSlMarginPct() / 100.0), MC);
        BigDecimal adjustedSl = rawSl.subtract(margin, MC);

        BigDecimal risk         = entry.subtract(adjustedSl).abs(MC);
        BigDecimal partialTarget = entry.add(risk.multiply(bd(req.getPartialExitRR()), MC), MC);
        BigDecimal fullTarget    = entry.add(risk.multiply(bd(req.getRiskRewardRatio()), MC), MC);
        BigDecimal trailedSl     = entry;  // breakeven after partial

        return simulate(req, signal, refCandle, allCandles, signalIdx,
                TradeRecord.Direction.LONG,
                entry, rawSl, adjustedSl, risk, partialTarget, fullTarget, trailedSl);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Simulation: walk forward candle by candle after entry
    // ─────────────────────────────────────────────────────────────────────────

    private TradeRecord simulate(BacktestRequest req,
                                 Candle signal, Candle refCandle,
                                 List<Candle> allCandles, int signalIdx,
                                 TradeRecord.Direction direction,
                                 BigDecimal entry, BigDecimal rawSl, BigDecimal adjustedSl,
                                 BigDecimal risk, BigDecimal partialTarget,
                                 BigDecimal fullTarget, BigDecimal trailedSl) {

        boolean isShort = direction == TradeRecord.Direction.SHORT;

        boolean partialHit     = false;
        BigDecimal activeSl    = adjustedSl;  // SL starts as the initial SL, may trail to entry

        TradeRecord.ExitReason exitReason = TradeRecord.ExitReason.OPEN;
        BigDecimal   exitPrice            = null;
        BigDecimal   partialExitPrice     = null;

        // Walk forward from the candle AFTER the signal
        for (int j = signalIdx + 1; j < allCandles.size(); j++) {
            Candle c = allCandles.get(j);

            if (isShort) {
                // ── SHORT: price goes DOWN is good ──────────────────────────

                // Check partial target first (candle low touches/crosses partialTarget)
                if (!partialHit && c.getLow().compareTo(partialTarget) <= 0) {
                    partialHit     = true;
                    partialExitPrice = partialTarget;
                    activeSl       = trailedSl;   // trail SL to breakeven (entry)
                    log.debug("SHORT partial exit @ {} for signal {}", partialTarget, signal.getCloseTime());
                }

                // Check SL (candle high touches/crosses activeSl)
                if (c.getHigh().compareTo(activeSl) >= 0) {
                    exitPrice  = activeSl;
                    exitReason = partialHit
                            ? TradeRecord.ExitReason.PARTIAL_THEN_SL
                            : TradeRecord.ExitReason.STOP_LOSS;
                    break;
                }

                // Check full target (candle low touches/crosses fullTarget)
                if (c.getLow().compareTo(fullTarget) <= 0) {
                    exitPrice  = fullTarget;
                    exitReason = partialHit
                            ? TradeRecord.ExitReason.PARTIAL_THEN_TARGET
                            : TradeRecord.ExitReason.FULL_TARGET;
                    break;
                }

            } else {
                // ── LONG: price goes UP is good ──────────────────────────────

                // Check partial target (candle high touches/crosses partialTarget)
                if (!partialHit && c.getHigh().compareTo(partialTarget) >= 0) {
                    partialHit     = true;
                    partialExitPrice = partialTarget;
                    activeSl       = trailedSl;   // trail SL to breakeven
                    log.debug("LONG partial exit @ {} for signal {}", partialTarget, signal.getCloseTime());
                }

                // Check SL (candle low touches/crosses activeSl)
                if (c.getLow().compareTo(activeSl) <= 0) {
                    exitPrice  = activeSl;
                    exitReason = partialHit
                            ? TradeRecord.ExitReason.PARTIAL_THEN_SL
                            : TradeRecord.ExitReason.STOP_LOSS;
                    break;
                }

                // Check full target (candle high touches/crosses fullTarget)
                if (c.getHigh().compareTo(partialTarget) >= 0   // partial already handled above
                        && partialHit
                        && c.getHigh().compareTo(fullTarget) >= 0) {
                    exitPrice  = fullTarget;
                    exitReason = TradeRecord.ExitReason.PARTIAL_THEN_TARGET;
                    break;
                }
                if (!partialHit && c.getHigh().compareTo(fullTarget) >= 0) {
                    exitPrice  = fullTarget;
                    exitReason = TradeRecord.ExitReason.FULL_TARGET;
                    break;
                }
            }
        }

        // ── Calculate P&L in R-multiples ──────────────────────────────────────
        BigDecimal pnlR = calcPnlR(req, direction, entry, risk,
                partialHit, partialExitPrice, exitPrice, exitReason);

        // Weighted average exit price
        BigDecimal avgExit = calcAvgExitPrice(req, partialHit, partialExitPrice, exitPrice);

        Candle lastCandle = allCandles.get(allCandles.size() - 1);

        return TradeRecord.builder()
                .symbol(req.getSymbol())
                .direction(direction)
                .signalCandleTime(signal.getCloseTime())
                .refCandleTime(refCandle.getOpenTime())
                .entry(entry)
                .rawSl(rawSl)
                .adjustedSl(adjustedSl)
                .risk(risk)
                .partialTarget(partialTarget)
                .fullTarget(fullTarget)
                .trailedSl(trailedSl)
                .entryTime(signal.getCloseTime())
                .exitTime(exitPrice != null ? lastCandle.getCloseTime() : null)
                .exitReason(exitReason)
                .avgExitPrice(avgExit)
                .pnlR(pnlR)
                .partialExitTriggered(partialHit)
                .partialExitPrice(partialExitPrice)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P&L calculation in R-multiples
    //
    // Position is split into two halves:
    //   50% (partialQtyPct) exits at partial target  → contributes partialRR × halfWeight
    //   50% exits at full target or SL              → contributes accordingly
    //
    //   PARTIAL_THEN_TARGET  : 0.5×partialRR + 0.5×fullRR
    //   PARTIAL_THEN_SL      : 0.5×partialRR + 0.5×0  (SL=entry → 0R on that leg)
    //   FULL_TARGET (no partial): 1×fullRR
    //   STOP_LOSS   (no partial): -1R
    // ─────────────────────────────────────────────────────────────────────────

    private BigDecimal calcPnlR(BacktestRequest req,
                                TradeRecord.Direction dir,
                                BigDecimal entry, BigDecimal risk,
                                boolean partialHit, BigDecimal partialPrice,
                                BigDecimal finalPrice, TradeRecord.ExitReason reason) {

        double halfQty    = req.getPartialExitQtyPct() / 100.0;
        double remainQty  = 1.0 - halfQty;

        switch (reason) {
            case FULL_TARGET:
                // No partial triggered — full size hits full target
                return bd(req.getRiskRewardRatio());

            case PARTIAL_THEN_TARGET:
                // 50% at partialRR, 50% at fullRR
                return bd(halfQty * req.getPartialExitRR()
                        + remainQty * req.getRiskRewardRatio()).setScale(4, RoundingMode.HALF_UP);

            case PARTIAL_THEN_SL:
                // 50% at partialRR, 50% at breakeven (0R)
                return bd(halfQty * req.getPartialExitRR()).setScale(4, RoundingMode.HALF_UP);

            case STOP_LOSS:
                return bd(-1.0);

            default:
                return BigDecimal.ZERO;  // OPEN / unresolved
        }
    }

    private BigDecimal calcAvgExitPrice(BacktestRequest req,
                                        boolean partialHit,
                                        BigDecimal partialPrice,
                                        BigDecimal finalPrice) {
        if (!partialHit || partialPrice == null) {
            return finalPrice;
        }
        if (finalPrice == null) return partialPrice;

        double halfQty   = req.getPartialExitQtyPct() / 100.0;
        double remainQty = 1.0 - halfQty;

        return partialPrice.multiply(bd(halfQty), MC)
                .add(finalPrice.multiply(bd(remainQty), MC), MC)
                .setScale(8, RoundingMode.HALF_UP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Aggregate statistics
    // ─────────────────────────────────────────────────────────────────────────

    private BacktestResult aggregate(BacktestRequest req, List<TradeRecord> trades) {
        int wins = 0, partialWins = 0, losses = 0, openTrades = 0;
        int longs = 0, shorts = 0;
        BigDecimal totalR = BigDecimal.ZERO;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss   = BigDecimal.ZERO;
        BigDecimal maxWinR     = BigDecimal.valueOf(Double.MIN_VALUE);
        BigDecimal maxLossR    = BigDecimal.valueOf(Double.MAX_VALUE);
        BigDecimal sumWinR     = BigDecimal.ZERO;
        BigDecimal sumLossR    = BigDecimal.ZERO;

        int consecWins = 0, consecLosses = 0, maxCW = 0, maxCL = 0;
        BigDecimal drawdown = BigDecimal.ZERO, maxDrawdown = BigDecimal.ZERO;
        BigDecimal peak     = BigDecimal.ZERO, equity       = BigDecimal.ZERO;

        for (TradeRecord t : trades) {
            if (t.getDirection() == TradeRecord.Direction.LONG) longs++;
            else shorts++;

            BigDecimal r = t.getPnlR() != null ? t.getPnlR() : BigDecimal.ZERO;
            totalR = totalR.add(r, MC);
            equity = equity.add(r, MC);

            // Drawdown tracking
            if (equity.compareTo(peak) > 0) peak = equity;
            drawdown = peak.subtract(equity, MC);
            if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;

            switch (t.getExitReason()) {
                case FULL_TARGET, PARTIAL_THEN_TARGET -> {
                    wins++;
                    grossProfit = grossProfit.add(r, MC);
                    sumWinR     = sumWinR.add(r, MC);
                    if (r.compareTo(maxWinR) > 0) maxWinR = r;
                    consecWins++; consecLosses = 0;
                    if (consecWins > maxCW) maxCW = consecWins;
                }
                case PARTIAL_THEN_SL -> {
                    partialWins++;
                    grossProfit = grossProfit.add(r, MC);
                    sumWinR     = sumWinR.add(r, MC);
                    if (r.compareTo(maxWinR) > 0) maxWinR = r;
                    consecWins++; consecLosses = 0;
                    if (consecWins > maxCW) maxCW = consecWins;
                }
                case STOP_LOSS -> {
                    losses++;
                    grossLoss = grossLoss.add(r.abs(), MC);
                    sumLossR  = sumLossR.add(r, MC);
                    if (r.compareTo(maxLossR) < 0) maxLossR = r;
                    consecLosses++; consecWins = 0;
                    if (consecLosses > maxCL) maxCL = consecLosses;
                }
                default -> openTrades++;
            }
        }

        int closedTrades = wins + partialWins + losses;
        BigDecimal winRate = closedTrades > 0
                ? bd((double)(wins + partialWins) / closedTrades * 100).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal profitFactor = grossLoss.compareTo(BigDecimal.ZERO) != 0
                ? grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP)
                : grossProfit;

        int winCount  = wins + partialWins;
        BigDecimal avgWinR  = winCount  > 0 ? sumWinR.divide(bd(winCount),  4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgLossR = losses    > 0 ? sumLossR.divide(bd(losses),   4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgPnlR  = closedTrades > 0
                ? totalR.subtract(openTrades > 0 ? BigDecimal.ZERO : BigDecimal.ZERO)
                .divide(bd(closedTrades), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return BacktestResult.builder()
                .symbol(req.getSymbol())
                .fromDate(req.getFromDate())
                .toDate(req.getToDate())
                .slMarginPct(req.getSlMarginPct())
                .riskRewardRatio(req.getRiskRewardRatio())
                .partialExitRR(req.getPartialExitRR())
                .partialExitQtyPct(req.getPartialExitQtyPct())
                .totalTrades(trades.size())
                .totalLong(longs)
                .totalShort(shorts)
                .wins(wins)
                .partialWins(partialWins)
                .losses(losses)
                .openTrades(openTrades)
                .winRate(winRate)
                .totalPnlR(totalR.setScale(4, RoundingMode.HALF_UP))
                .avgPnlR(avgPnlR)
                .maxDrawdownR(maxDrawdown.setScale(4, RoundingMode.HALF_UP))
                .profitFactor(profitFactor)
                .avgWinR(avgWinR)
                .avgLossR(avgLossR)
                .largestWinR(maxWinR.compareTo(BigDecimal.valueOf(Double.MIN_VALUE)) == 0 ? BigDecimal.ZERO : maxWinR.setScale(4, RoundingMode.HALF_UP))
                .largestLossR(maxLossR.compareTo(BigDecimal.valueOf(Double.MAX_VALUE)) == 0 ? BigDecimal.ZERO : maxLossR.setScale(4, RoundingMode.HALF_UP))
                .maxConsecWins(maxCW)
                .maxConsecLosses(maxCL)
                .trades(trades)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Daily high/low map helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Builds a map of "yyyy-MM-dd" → DayHighLow from the 15m candle list */
    private Map<String, DayHighLow> buildDailyMap(List<Candle> candles) {
        Map<String, DayHighLow> map = new LinkedHashMap<>();
        for (Candle c : candles) {
            String key = c.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate().toString();
            map.compute(key, (k, existing) -> {
                if (existing == null) {
                    return new DayHighLow(c.getHigh(), c.getLow());
                }
                existing.high = existing.high.max(c.getHigh());
                existing.low  = existing.low.min(c.getLow());
                return existing;
            });
        }
        return map;
    }

    /** Day key for the candle's own date */
    private String dayKey(Candle c) {
        return c.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** Day key for the PREVIOUS day (used to look up PDL/PDH) */
    private String prevDayKey(Candle c) {
        return c.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private BacktestResult emptyResult(BacktestRequest req) {
        return BacktestResult.builder()
                .symbol(req.getSymbol())
                .fromDate(req.getFromDate())
                .toDate(req.getToDate())
                .totalTrades(0)
                .trades(List.of())
                .build();
    }

    /** Simple mutable holder used while building the daily map */
    private static class DayHighLow {
        BigDecimal high;
        BigDecimal low;
        DayHighLow(BigDecimal h, BigDecimal l) { this.high = h; this.low = l; }
    }
}