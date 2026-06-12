package com.trading.algo.delta.service;

import com.trading.algo.delta.model.*;
import com.trading.algo.delta.model.VolumeTradeRecord.Direction;
import com.trading.algo.delta.model.VolumeTradeRecord.ExitReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Volume-spike backtest engine.
 *
 * SIGNAL DETECTION  — 15-min candles, rolling 20-candle avg volume:
 *   Spike     : volume >= spikeMultiplier (default 2x) × avg
 *   Climax    : volume >= climaxMultiplier (default 3x) × avg AND 5 consecutive same-direction closes
 *
 * CLASSIFICATION → ENTRY RULES:
 *
 *   BREAKOUT  (big body >= 50% of range)
 *     → Enter in direction of the breakout candle at close
 *     → SL: below spike candle low  (LONG) or above spike candle high (SHORT) + margin
 *     → Target: breakoutRR (default 3R)
 *
 *   ABSORPTION (small body < 50% of range, no clear direction)
 *     → HIGH volume + NO move = buyers/sellers absorbed → expect reversal
 *     → Wait for the NEXT candle to confirm reversal direction, then enter
 *     → SL: beyond spike candle extreme (opposite side) + margin
 *     → Target: absorptionRR (default 2R)
 *
 *   CLIMAX (3x+ volume + prior trend of 5 same-direction candles)
 *     → Trend exhaustion → FADE the move (trade against prior trend)
 *     → Enter at close of climax candle in OPPOSITE direction to prior trend
 *     → SL: climax candle extreme in trend direction + margin
 *     → Target: climaxRR (default 2R)
 *
 * RISK MANAGEMENT:
 *   Risk = entry × riskPercent / 100  (default 1% of price)
 *   If spike candle range > riskPercent of entry → use candle extreme as SL
 *   (meaning natural SL > 1R; position size adjusted so risk stays at 1R monetary)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolumeBacktestEngine {

    private static final int    LOOKBACK   = 20;
    private static final double  BODY_RATIO = 0.50;
    private static final MathContext MC     = MathContext.DECIMAL64;

    private final DeltaApiService deltaApiService;

    /** Holds the nearest S/R match for a signal candle — null means no level nearby */
    private record SrMatch(BigDecimal level, String type) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    public VolumeBacktestResult run(VolumeBacktestRequest req) {
        log.info("Volume backtest START: symbol={} from={} to={}", req.getSymbol(), req.getFromDate(), req.getToDate());

        long windowStart = req.getFromDate().minusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long windowEnd   = req.getToDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

        List<Candle> allCandles = deltaApiService.get15mCandles(req.getSymbol(), windowStart, windowEnd)
                .stream().filter(Candle::isClosed).collect(Collectors.toList());

        log.info("Fetched {} closed 15m candles for {}", allCandles.size(), req.getSymbol());

        if (allCandles.size() < LOOKBACK + 2) {
            log.warn("Not enough candles to backtest.");
            return emptyResult(req);
        }

        List<VolumeTradeRecord> trades = new ArrayList<>();

        // We need LOOKBACK candles before the signal + 1 confirmation candle for ABSORPTION
        for (int i = LOOKBACK; i < allCandles.size(); i++) {
            Candle signal = allCandles.get(i);

            // Only evaluate candles in the requested date window
            if (signal.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate().isBefore(req.getFromDate())) continue;
            if (signal.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate().isAfter(req.getToDate())) break;

            List<Candle> lookback = allCandles.subList(i - LOOKBACK, i);
            BigDecimal avgVol = average(lookback);
            if (avgVol.compareTo(BigDecimal.ZERO) == 0) continue;

            double ratio = signal.getVolume().divide(avgVol, 4, RoundingMode.HALF_UP).doubleValue();
            if (ratio < req.getSpikeMultiplier()) continue;

            VolumeSignal.Type type = classify(signal, lookback, ratio, req.getClimaxMultiplier());
            log.debug("{} | {} | type={} | ratio={}", signal.getOpenTime(), req.getSymbol(), type, ratio);

            // ── S/R proximity check ───────────────────────────────────────
            SrMatch srMatch = findNearestSrLevel(
                    signal, lookback, req.getSrPivotStrength(), req.getSrProximityPct());

            if (req.isSrFilterEnabled() && srMatch == null) {
                log.debug("{} | {} | SKIPPED — not near S/R level", signal.getOpenTime(), req.getSymbol());
                continue;
            }

            VolumeTradeRecord trade = switch (type) {
                case BREAKOUT   -> buildBreakoutTrade(req, signal, allCandles, i, ratio, srMatch);
                case ABSORPTION -> buildAbsorptionTrade(req, signal, allCandles, i, ratio, srMatch);
                case CLIMAX     -> buildClimaxTrade(req, signal, allCandles, i, ratio, srMatch);
            };

            if (trade != null) {
                trades.add(trade);
                log.debug("  Trade added: {} {} {} entry={} sl={} target={} near={}",
                        type, trade.getDirection(), signal.getOpenTime(),
                        trade.getEntry(), trade.getStopLoss(), trade.getTarget(),
                        srMatch != null ? srMatch.level() : "none");
            }
        }

        log.info("Volume backtest COMPLETE: {} trades for {}", trades.size(), req.getSymbol());
        return aggregate(req, trades);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Signal classification
    // ─────────────────────────────────────────────────────────────────────────

    private VolumeSignal.Type classify(Candle c, List<Candle> lookback, double ratio, double climaxMultiplier) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        BigDecimal body  = c.getClose().subtract(c.getOpen()).abs();

        boolean bigBody = range.compareTo(BigDecimal.ZERO) > 0
                && body.divide(range, 4, RoundingMode.HALF_UP).doubleValue() >= BODY_RATIO;

        if (ratio >= climaxMultiplier && isTrending(lookback)) return VolumeSignal.Type.CLIMAX;
        if (bigBody) return VolumeSignal.Type.BREAKOUT;
        return VolumeSignal.Type.ABSORPTION;
    }

    private boolean isTrending(List<Candle> lookback) {
        int n = lookback.size();
        if (n < 5) return false;
        List<Candle> last5 = lookback.subList(n - 5, n);
        long ups   = last5.stream().filter(c -> c.getClose().compareTo(c.getOpen()) > 0).count();
        long downs = last5.stream().filter(c -> c.getClose().compareTo(c.getOpen()) < 0).count();
        return ups == 5 || downs == 5;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // S/R level detection — pure price-action swing pivot method
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Left-only pivot: candle[j].high is a swing high if it is strictly greater
     * than all candles in [j-strength .. j-1]. Same logic for swing lows.
     * Returns the nearest level within srProximityPct, or null.
     */
    private SrMatch findNearestSrLevel(Candle signal, List<Candle> lookback,
                                       int strength, double proximityPct) {
        BigDecimal close       = signal.getClose();
        double     proximity   = proximityPct / 100.0;
        SrMatch    best        = null;
        double     bestDistPct = Double.MAX_VALUE;

        int n = lookback.size();
        for (int j = strength; j < n; j++) {
            Candle c = lookback.get(j);

            boolean isPivotHigh = true;
            for (int k = j - strength; k < j; k++) {
                if (lookback.get(k).getHigh().compareTo(c.getHigh()) >= 0) { isPivotHigh = false; break; }
            }
            if (isPivotHigh) {
                double d = close.subtract(c.getHigh()).abs().divide(close, 8, RoundingMode.HALF_UP).doubleValue();
                if (d <= proximity && d < bestDistPct) { bestDistPct = d; best = new SrMatch(c.getHigh(), "RESISTANCE"); }
            }

            boolean isPivotLow = true;
            for (int k = j - strength; k < j; k++) {
                if (lookback.get(k).getLow().compareTo(c.getLow()) <= 0) { isPivotLow = false; break; }
            }
            if (isPivotLow) {
                double d = close.subtract(c.getLow()).abs().divide(close, 8, RoundingMode.HALF_UP).doubleValue();
                if (d <= proximity && d < bestDistPct) { bestDistPct = d; best = new SrMatch(c.getLow(), "SUPPORT"); }
            }
        }
        return best;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry builders — one per signal type
    // ─────────────────────────────────────────────────────────────────────────

    private VolumeTradeRecord buildBreakoutTrade(VolumeBacktestRequest req, Candle signal,
                                                  List<Candle> all, int idx, double ratio, SrMatch srMatch) {
        boolean bullish = signal.getClose().compareTo(signal.getOpen()) > 0;
        Direction dir   = bullish ? Direction.LONG : Direction.SHORT;

        BigDecimal entry = signal.getClose();
        BigDecimal sl    = bullish
                ? signal.getLow().multiply(bd(1.0 - req.getSlMarginPct() / 100.0), MC)
                : signal.getHigh().multiply(bd(1.0 + req.getSlMarginPct() / 100.0), MC);

        BigDecimal risk = computeRisk(entry, sl, req.getRiskPercent());
        sl = adjustSlToRisk(dir, entry, risk);  // ensure SL matches 1% risk cap

        BigDecimal target = dir == Direction.LONG
                ? entry.add(risk.multiply(bd(req.getBreakoutRR()), MC), MC)
                : entry.subtract(risk.multiply(bd(req.getBreakoutRR()), MC), MC);

        return simulate(req, signal, dir, VolumeSignal.Type.BREAKOUT, entry, sl, target, risk,
                bd(ratio), all, idx, srMatch);
    }

    /**
     * ABSORPTION: high volume + small body (no move) → wait for NEXT candle to confirm direction,
     * then fade the spike (trade the reversal).
     * Entry at next candle close, SL beyond spike candle extreme.
     */
    private VolumeTradeRecord buildAbsorptionTrade(VolumeBacktestRequest req, Candle signal,
                                                    List<Candle> all, int idx, double ratio, SrMatch srMatch) {
        // Need at least 1 more candle for confirmation
        if (idx + 1 >= all.size()) return null;

        Candle confirm = all.get(idx + 1);

        // Confirmation: next candle closes in opposite direction to the spike candle body
        // or if spike candle was doji — use next candle close vs spike candle close
        boolean confirmBullish = confirm.getClose().compareTo(signal.getClose()) > 0;
        boolean confirmBearish = confirm.getClose().compareTo(signal.getClose()) < 0;

        if (!confirmBullish && !confirmBearish) return null;  // flat candle, skip

        Direction dir = confirmBullish ? Direction.LONG : Direction.SHORT;

        BigDecimal entry = confirm.getClose();
        BigDecimal sl    = dir == Direction.LONG
                ? signal.getLow().multiply(bd(1.0 - req.getSlMarginPct() / 100.0), MC)   // below spike low
                : signal.getHigh().multiply(bd(1.0 + req.getSlMarginPct() / 100.0), MC); // above spike high

        BigDecimal risk = computeRisk(entry, sl, req.getRiskPercent());
        sl = adjustSlToRisk(dir, entry, risk);

        BigDecimal target = dir == Direction.LONG
                ? entry.add(risk.multiply(bd(req.getAbsorptionRR()), MC), MC)
                : entry.subtract(risk.multiply(bd(req.getAbsorptionRR()), MC), MC);

        // Simulate from confirmation candle onwards (idx + 2)
        return simulate(req, confirm, dir, VolumeSignal.Type.ABSORPTION, entry, sl, target, risk,
                bd(ratio), all, idx + 1, srMatch);
    }

    /**
     * CLIMAX: 3x volume + 5-candle trend = exhaustion → FADE the trend.
     * Enter at close of climax candle in OPPOSITE direction to prior trend.
     * SL: extreme of climax candle in trend direction + margin.
     */
    private VolumeTradeRecord buildClimaxTrade(VolumeBacktestRequest req, Candle signal,
                                                List<Candle> all, int idx, double ratio, SrMatch srMatch) {
        // Determine trend direction from the 5 candles before the climax
        int n = idx;
        if (n < 5) return null;
        List<Candle> last5 = all.subList(n - 5, n);
        long ups = last5.stream().filter(c -> c.getClose().compareTo(c.getOpen()) > 0).count();
        boolean priorTrendUp = ups == 5;

        // Fade: if prior trend was UP → we go SHORT (exhaustion), if DOWN → we go LONG
        Direction dir = priorTrendUp ? Direction.SHORT : Direction.LONG;

        BigDecimal entry = signal.getClose();
        BigDecimal sl    = priorTrendUp
                ? signal.getHigh().multiply(bd(1.0 + req.getSlMarginPct() / 100.0), MC)  // above climax high
                : signal.getLow().multiply(bd(1.0 - req.getSlMarginPct() / 100.0), MC);  // below climax low

        BigDecimal risk = computeRisk(entry, sl, req.getRiskPercent());
        sl = adjustSlToRisk(dir, entry, risk);

        BigDecimal target = dir == Direction.LONG
                ? entry.add(risk.multiply(bd(req.getClimaxRR()), MC), MC)
                : entry.subtract(risk.multiply(bd(req.getClimaxRR()), MC), MC);

        return simulate(req, signal, dir, VolumeSignal.Type.CLIMAX, entry, sl, target, risk,
                bd(ratio), all, idx, srMatch);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Simulation: walk forward candle-by-candle after entry
    // ─────────────────────────────────────────────────────────────────────────

    private VolumeTradeRecord simulate(VolumeBacktestRequest req,
                                       Candle entryCandl,
                                       Direction dir,
                                       VolumeSignal.Type type,
                                       BigDecimal entry,
                                       BigDecimal sl,
                                       BigDecimal target,
                                       BigDecimal risk,
                                       BigDecimal volumeRatio,
                                       List<Candle> all,
                                       int entryIdx,
                                       SrMatch srMatch) {

        ExitReason   exitReason = ExitReason.OPEN;
        BigDecimal   exitPrice  = null;
        Candle       exitCandle = null;

        double rr = type == VolumeSignal.Type.BREAKOUT ? req.getBreakoutRR()
                  : type == VolumeSignal.Type.ABSORPTION ? req.getAbsorptionRR()
                  : req.getClimaxRR();

        for (int j = entryIdx + 1; j < all.size(); j++) {
            Candle c = all.get(j);

            if (dir == Direction.LONG) {
                if (c.getLow().compareTo(sl) <= 0) {
                    exitPrice = sl; exitReason = ExitReason.STOP_LOSS; exitCandle = c; break;
                }
                if (c.getHigh().compareTo(target) >= 0) {
                    exitPrice = target; exitReason = ExitReason.FULL_TARGET; exitCandle = c; break;
                }
            } else {
                if (c.getHigh().compareTo(sl) >= 0) {
                    exitPrice = sl; exitReason = ExitReason.STOP_LOSS; exitCandle = c; break;
                }
                if (c.getLow().compareTo(target) <= 0) {
                    exitPrice = target; exitReason = ExitReason.FULL_TARGET; exitCandle = c; break;
                }
            }
        }

        BigDecimal pnlR = switch (exitReason) {
            case FULL_TARGET -> bd(rr);
            case STOP_LOSS   -> bd(-1.0);
            default          -> BigDecimal.ZERO;
        };

        return VolumeTradeRecord.builder()
                .symbol(req.getSymbol())
                .direction(dir)
                .signalType(type)
                .signalCandleTime(entryCandl.getOpenTime())
                .volumeRatio(volumeRatio)
                .entry(entry)
                .stopLoss(sl)
                .target(target)
                .risk(risk)
                .rewardPoints(target.subtract(entry).abs())
                .exitTime(exitCandle != null ? exitCandle.getCloseTime() : null)
                .exitReason(exitReason)
                .exitPrice(exitPrice)
                .pnlR(pnlR)
                .nearSrLevel(srMatch != null)
                .srLevel(srMatch != null ? srMatch.level() : null)
                .srLevelType(srMatch != null ? srMatch.type() : null)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Risk helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Risk = larger of (entry-sl absolute) OR (1% of entry).
     * Always ensures risk stays at least 1R of entry price.
     */
    private BigDecimal computeRisk(BigDecimal entry, BigDecimal sl, double riskPercent) {
        BigDecimal naturalRisk = entry.subtract(sl).abs();
        BigDecimal onePercent  = entry.multiply(bd(riskPercent / 100.0), MC);
        return naturalRisk.max(onePercent);
    }

    /**
     * Recalculate SL from entry using 1% risk so SL is always exactly 1R away.
     * This is the standard "1% risk management" rule.
     */
    private BigDecimal adjustSlToRisk(Direction dir, BigDecimal entry, BigDecimal risk) {
        return dir == Direction.LONG
                ? entry.subtract(risk, MC)
                : entry.add(risk, MC);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Aggregation — mirrors BacktestEngine.aggregate()
    // ─────────────────────────────────────────────────────────────────────────

    private VolumeBacktestResult aggregate(VolumeBacktestRequest req, List<VolumeTradeRecord> trades) {
        int wins = 0, losses = 0, openTrades = 0;
        BigDecimal totalR     = BigDecimal.ZERO;
        BigDecimal grossProfit= BigDecimal.ZERO;
        BigDecimal grossLoss  = BigDecimal.ZERO;
        BigDecimal maxWinR    = BigDecimal.valueOf(Double.MIN_VALUE);
        BigDecimal maxLossR   = BigDecimal.valueOf(Double.MAX_VALUE);
        BigDecimal sumWinR    = BigDecimal.ZERO;
        BigDecimal sumLossR   = BigDecimal.ZERO;

        int consecWins = 0, consecLosses = 0, maxCW = 0, maxCL = 0;
        BigDecimal equity = BigDecimal.ZERO, peak = BigDecimal.ZERO, maxDrawdown = BigDecimal.ZERO;

        for (VolumeTradeRecord t : trades) {
            BigDecimal r = t.getPnlR() != null ? t.getPnlR() : BigDecimal.ZERO;
            totalR = totalR.add(r, MC);
            equity = equity.add(r, MC);

            if (equity.compareTo(peak) > 0) peak = equity;
            BigDecimal dd = peak.subtract(equity, MC);
            if (dd.compareTo(maxDrawdown) > 0) maxDrawdown = dd;

            switch (t.getExitReason()) {
                case FULL_TARGET -> {
                    wins++;
                    grossProfit = grossProfit.add(r, MC);
                    sumWinR = sumWinR.add(r, MC);
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

        int closed = wins + losses;
        BigDecimal winRate     = closed > 0 ? bd((double) wins / closed * 100).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal profitFactor= grossLoss.compareTo(BigDecimal.ZERO) != 0 ? grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP) : grossProfit;
        BigDecimal avgWinR     = wins   > 0 ? sumWinR.divide(bd(wins),   4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgLossR    = losses > 0 ? sumLossR.divide(bd(losses), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgPnlR     = closed > 0 ? totalR.divide(bd(closed),  4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        return VolumeBacktestResult.builder()
                .symbol(req.getSymbol())
                .fromDate(req.getFromDate()).toDate(req.getToDate())
                .spikeMultiplier(req.getSpikeMultiplier()).climaxMultiplier(req.getClimaxMultiplier())
                .riskPercent(req.getRiskPercent())
                .breakoutRR(req.getBreakoutRR()).absorptionRR(req.getAbsorptionRR()).climaxRR(req.getClimaxRR())
                .totalTrades(trades.size()).wins(wins).losses(losses).openTrades(openTrades)
                .winRate(winRate).totalPnlR(totalR.setScale(4, RoundingMode.HALF_UP))
                .avgPnlR(avgPnlR).profitFactor(profitFactor)
                .maxDrawdownR(maxDrawdown.setScale(4, RoundingMode.HALF_UP))
                .avgWinR(avgWinR).avgLossR(avgLossR)
                .largestWinR(maxWinR.compareTo(BigDecimal.valueOf(Double.MIN_VALUE)) == 0 ? BigDecimal.ZERO : maxWinR.setScale(4, RoundingMode.HALF_UP))
                .largestLossR(maxLossR.compareTo(BigDecimal.valueOf(Double.MAX_VALUE)) == 0 ? BigDecimal.ZERO : maxLossR.setScale(4, RoundingMode.HALF_UP))
                .maxConsecWins(maxCW).maxConsecLosses(maxCL)
                .breakoutStats(buildTypeStat(trades, VolumeSignal.Type.BREAKOUT))
                .absorptionStats(buildTypeStat(trades, VolumeSignal.Type.ABSORPTION))
                .climaxStats(buildTypeStat(trades, VolumeSignal.Type.CLIMAX))
                .trades(trades)
                .build();
    }

    private VolumeBacktestResult.SignalTypeStat buildTypeStat(List<VolumeTradeRecord> trades, VolumeSignal.Type type) {
        List<VolumeTradeRecord> sub = trades.stream().filter(t -> t.getSignalType() == type).toList();
        int total = sub.size();
        if (total == 0) return VolumeBacktestResult.SignalTypeStat.builder().type(type).total(0).wins(0).losses(0)
                .winRate(BigDecimal.ZERO).totalPnlR(BigDecimal.ZERO).avgPnlR(BigDecimal.ZERO).build();

        int w  = (int) sub.stream().filter(t -> t.getExitReason() == ExitReason.FULL_TARGET).count();
        int l  = (int) sub.stream().filter(t -> t.getExitReason() == ExitReason.STOP_LOSS).count();
        BigDecimal sumR  = sub.stream().map(VolumeTradeRecord::getPnlR).reduce(BigDecimal.ZERO, BigDecimal::add);
        int closed = w + l;
        BigDecimal wr   = closed > 0 ? bd((double) w / closed * 100).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgR = closed > 0 ? sumR.divide(bd(closed), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        return VolumeBacktestResult.SignalTypeStat.builder()
                .type(type).total(total).wins(w).losses(l)
                .winRate(wr).totalPnlR(sumR.setScale(4, RoundingMode.HALF_UP)).avgPnlR(avgR)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private BigDecimal average(List<Candle> candles) {
        BigDecimal sum = candles.stream().map(Candle::getVolume).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(candles.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    private VolumeBacktestResult emptyResult(VolumeBacktestRequest req) {
        return VolumeBacktestResult.builder()
                .symbol(req.getSymbol()).fromDate(req.getFromDate()).toDate(req.getToDate())
                .totalTrades(0).trades(List.of()).build();
    }
}
