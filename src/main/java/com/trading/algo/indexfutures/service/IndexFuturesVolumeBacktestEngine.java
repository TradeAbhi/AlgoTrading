package com.trading.algo.indexfutures.service;

import com.trading.algo.dtos.Candle;
import com.trading.algo.indexfutures.model.IndexFuturesVolumeBacktestResult;
import com.trading.algo.indexfutures.model.IndexFuturesVolumeBacktestResult.SignalTypeStat;
import com.trading.algo.indexfutures.model.IndexFuturesVolumeScanRequest;
import com.trading.algo.indexfutures.model.IndexFuturesVolumeTradeRecord;
import com.trading.algo.indexfutures.model.IndexFuturesVolumeTradeRecord.Direction;
import com.trading.algo.indexfutures.model.IndexFuturesVolumeTradeRecord.ExitReason;
import com.trading.algo.indexfutures.model.IndexFuturesVolumeTradeRecord.SignalType;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Backtest engine for the Index Futures Volume Spike strategy.
 *
 * SIGNAL DETECTION — 15-min candles, rolling 20-candle avg volume:
 *
 *   BREAKOUT  : volume >= spikeMultiplier x avg  AND  body >= 50% of range
 *               -> Enter in direction of the spike candle at close
 *               -> SL: spike candle low (LONG) or high (SHORT) + slMarginPct
 *               -> Target: breakoutRR
 *
 *   ABSORPTION: volume >= spikeMultiplier x avg  AND  body < 50% of range
 *               -> High volume + no move = supply/demand absorbed -> expect reversal
 *               -> Wait for NEXT candle to confirm direction, enter at its close
 *               -> SL: beyond spike candle extreme + slMarginPct
 *               -> Target: absorptionRR
 *
 *   CLIMAX    : volume >= climaxMultiplier x avg  AND  5 consecutive same-direction closes
 *               -> Trend exhaustion -> FADE the move (trade against prior trend)
 *               -> Enter at close of climax candle in OPPOSITE direction
 *               -> SL: climax candle extreme in trend direction + slMarginPct
 *               -> Target: climaxRR
 *
 * Time-of-day filter: signals only accepted between signalAfterHour and signalBeforeHour.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexFuturesVolumeBacktestEngine {

    private static final int    LOOKBACK   = 20;
    private static final double BODY_RATIO = 0.50;

    private final UpstoxHistoricalCandleService candleService;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public IndexFuturesVolumeBacktestResult run(IndexFuturesVolumeScanRequest req) {
        log.info("Index futures volume backtest START: {} from={} to={}", req.getLabel(), req.getFromDate(), req.getToDate());

        List<Candle> allCandles = new ArrayList<>();
        LocalDate day = req.getFromDate().minusDays(5); // extra days for lookback warmup
        while (!day.isAfter(req.getToDate())) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                List<Candle> dayCandles = candleService.fetchDayCandles(req.getInstrumentKey(), day);
                allCandles.addAll(dayCandles);
            }
            day = day.plusDays(1);
        }

        log.info("Fetched {} total 15m candles for {}", allCandles.size(), req.getLabel());

        if (allCandles.size() < LOOKBACK + 2) {
            log.warn("Not enough candles to backtest {}", req.getLabel());
            return emptyResult(req);
        }

        List<IndexFuturesVolumeTradeRecord> trades = new ArrayList<>();

        for (int i = LOOKBACK; i < allCandles.size(); i++) {
            Candle signal = allCandles.get(i);

            // Only evaluate candles within the requested date window
            LocalDate signalDate = signal.getTimestamp().toLocalDate();
            if (signalDate.isBefore(req.getFromDate())) continue;
            if (signalDate.isAfter(req.getToDate())) break;

            // Time-of-day filter
            int hour = signal.getTimestamp().getHour();
            if (hour < req.getSignalAfterHour() || hour >= req.getSignalBeforeHour()) continue;

            List<Candle> lookback = allCandles.subList(i - LOOKBACK, i);
            double avgVol = average(lookback);
            if (avgVol == 0) continue;

            double ratio = signal.getVolume() / avgVol;
            if (ratio < req.getSpikeMultiplier()) continue;

            SignalType type = classify(signal, lookback, ratio, req.getClimaxMultiplier());
            log.debug("{} | {} | type={} | ratio={:.2f}", signal.getTimestamp(), req.getLabel(), type, ratio);

            IndexFuturesVolumeTradeRecord trade = switch (type) {
                case BREAKOUT   -> buildBreakoutTrade(req, signal, allCandles, i, ratio, avgVol);
                case ABSORPTION -> buildAbsorptionTrade(req, signal, allCandles, i, ratio, avgVol);
                case CLIMAX     -> buildClimaxTrade(req, signal, allCandles, i, ratio, avgVol);
            };

            if (trade != null) {
                trades.add(trade);
                log.debug("  Trade: {} {} {} entry={} sl={} target={}",
                        type, trade.getDirection(), signal.getTimestamp(),
                        trade.getEntry(), trade.getStopLoss(), trade.getTarget());
            }
        }

        log.info("Index futures volume backtest COMPLETE: {} trades for {}", trades.size(), req.getLabel());
        return aggregate(req, trades);
    }

    // -------------------------------------------------------------------------
    // Signal classification
    // -------------------------------------------------------------------------

    private SignalType classify(Candle c, List<Candle> lookback, double ratio, double climaxMultiplier) {
        double range = c.range();
        double body  = c.body();
        boolean bigBody = range > 0 && (body / range) >= BODY_RATIO;

        if (ratio >= climaxMultiplier && isTrending(lookback)) return SignalType.CLIMAX;
        if (bigBody) return SignalType.BREAKOUT;
        return SignalType.ABSORPTION;
    }

    /** Strict trend check: last 5 closes all going same direction */
    private boolean isTrending(List<Candle> lookback) {
        int n = lookback.size();
        if (n < 5) return false;
        List<Candle> last5 = lookback.subList(n - 5, n);
        long ups   = last5.stream().filter(Candle::isBullish).count();
        long downs = last5.stream().filter(Candle::isBearish).count();
        return ups == 5 || downs == 5;
    }

    // -------------------------------------------------------------------------
    // Trade builders
    // -------------------------------------------------------------------------

    private IndexFuturesVolumeTradeRecord buildBreakoutTrade(IndexFuturesVolumeScanRequest req,
            Candle signal, List<Candle> all, int idx, double ratio, double avgVol) {

        boolean bullish = signal.isBullish();
        Direction dir   = bullish ? Direction.LONG : Direction.SHORT;

        double entry = signal.getClose();
        double sl    = bullish
                ? signal.getLow()  * (1.0 - req.getSlMarginPct() / 100.0)
                : signal.getHigh() * (1.0 + req.getSlMarginPct() / 100.0);

        double risk   = computeRisk(entry, sl, req.getRiskPercent());
        sl            = adjustSlToRisk(dir, entry, risk);
        double target = dir == Direction.LONG
                ? entry + risk * req.getBreakoutRR()
                : entry - risk * req.getBreakoutRR();

        return simulate(req, signal, dir, SignalType.BREAKOUT, entry, sl, target, risk,
                ratio, (long) avgVol, all, idx);
    }

    private IndexFuturesVolumeTradeRecord buildAbsorptionTrade(IndexFuturesVolumeScanRequest req,
            Candle signal, List<Candle> all, int idx, double ratio, double avgVol) {

        if (idx + 1 >= all.size()) return null;
        Candle confirm = all.get(idx + 1);

        // Confirmation: next candle closes above or below spike candle close
        boolean confirmBullish = confirm.getClose() > signal.getClose();
        boolean confirmBearish = confirm.getClose() < signal.getClose();
        if (!confirmBullish && !confirmBearish) return null;

        Direction dir = confirmBullish ? Direction.LONG : Direction.SHORT;

        double entry = confirm.getClose();
        double sl    = dir == Direction.LONG
                ? signal.getLow()  * (1.0 - req.getSlMarginPct() / 100.0)
                : signal.getHigh() * (1.0 + req.getSlMarginPct() / 100.0);

        double risk   = computeRisk(entry, sl, req.getRiskPercent());
        sl            = adjustSlToRisk(dir, entry, risk);
        double target = dir == Direction.LONG
                ? entry + risk * req.getAbsorptionRR()
                : entry - risk * req.getAbsorptionRR();

        return simulate(req, confirm, dir, SignalType.ABSORPTION, entry, sl, target, risk,
                ratio, (long) avgVol, all, idx + 1);
    }

    private IndexFuturesVolumeTradeRecord buildClimaxTrade(IndexFuturesVolumeScanRequest req,
            Candle signal, List<Candle> all, int idx, double ratio, double avgVol) {

        if (idx < 5) return null;
        List<Candle> last5 = all.subList(idx - 5, idx);
        long ups = last5.stream().filter(Candle::isBullish).count();
        boolean priorTrendUp = ups == 5;

        // Fade: prior trend UP -> go SHORT, prior trend DOWN -> go LONG
        Direction dir = priorTrendUp ? Direction.SHORT : Direction.LONG;

        double entry = signal.getClose();
        double sl    = priorTrendUp
                ? signal.getHigh() * (1.0 + req.getSlMarginPct() / 100.0)
                : signal.getLow()  * (1.0 - req.getSlMarginPct() / 100.0);

        double risk   = computeRisk(entry, sl, req.getRiskPercent());
        sl            = adjustSlToRisk(dir, entry, risk);
        double target = dir == Direction.LONG
                ? entry + risk * req.getClimaxRR()
                : entry - risk * req.getClimaxRR();

        return simulate(req, signal, dir, SignalType.CLIMAX, entry, sl, target, risk,
                ratio, (long) avgVol, all, idx);
    }

    // -------------------------------------------------------------------------
    // Walk-forward simulation
    // -------------------------------------------------------------------------

    private IndexFuturesVolumeTradeRecord simulate(IndexFuturesVolumeScanRequest req,
            Candle entryCandle, Direction dir, SignalType type,
            double entry, double sl, double target, double risk,
            double ratio, long avgVol, List<Candle> all, int entryIdx) {

        ExitReason exitReason = ExitReason.OPEN;
        double     exitPrice  = 0;
        LocalDateTime exitTime = null;

        double rr = switch (type) {
            case BREAKOUT   -> req.getBreakoutRR();
            case ABSORPTION -> req.getAbsorptionRR();
            case CLIMAX     -> req.getClimaxRR();
        };

        for (int j = entryIdx + 1; j < all.size(); j++) {
            Candle c = all.get(j);

            // Stop trading after EOD (3:15 PM) — no overnight holds
            if (c.getTimestamp().getHour() >= 15 && c.getTimestamp().getMinute() >= 15) {
                exitPrice  = c.getClose();
                exitReason = ExitReason.OPEN; // EOD exit, counted as open/neutral
                exitTime   = c.getTimestamp();
                break;
            }

            if (dir == Direction.LONG) {
                if (c.getLow() <= sl) {
                    exitPrice = sl; exitReason = ExitReason.STOP_LOSS; exitTime = c.getTimestamp(); break;
                }
                if (c.getHigh() >= target) {
                    exitPrice = target; exitReason = ExitReason.FULL_TARGET; exitTime = c.getTimestamp(); break;
                }
            } else {
                if (c.getHigh() >= sl) {
                    exitPrice = sl; exitReason = ExitReason.STOP_LOSS; exitTime = c.getTimestamp(); break;
                }
                if (c.getLow() <= target) {
                    exitPrice = target; exitReason = ExitReason.FULL_TARGET; exitTime = c.getTimestamp(); break;
                }
            }
        }

        double pnlR = switch (exitReason) {
            case FULL_TARGET -> rr;
            case STOP_LOSS   -> -1.0;
            default          -> 0.0;
        };

        return IndexFuturesVolumeTradeRecord.builder()
                .label(req.getLabel())
                .instrumentKey(req.getInstrumentKey())
                .signalType(type)
                .direction(dir)
                .signalCandleTime(entryCandle.getTimestamp())
                .volumeRatio(ratio)
                .signalVolume(entryCandle.getVolume())
                .avgVolume(avgVol)
                .signalOpen(entryCandle.getOpen())
                .signalHigh(entryCandle.getHigh())
                .signalLow(entryCandle.getLow())
                .signalClose(entryCandle.getClose())
                .entry(entry)
                .stopLoss(sl)
                .target(target)
                .riskPoints(risk)
                .rewardPoints(Math.abs(target - entry))
                .exitTime(exitTime)
                .exitReason(exitReason)
                .exitPrice(exitPrice)
                .pnlR(pnlR)
                .build();
    }

    // -------------------------------------------------------------------------
    // Risk helpers
    // -------------------------------------------------------------------------

    private double computeRisk(double entry, double sl, double riskPercent) {
        double naturalRisk = Math.abs(entry - sl);
        double onePercent  = entry * (riskPercent / 100.0);
        return Math.max(naturalRisk, onePercent);
    }

    private double adjustSlToRisk(Direction dir, double entry, double risk) {
        return dir == Direction.LONG ? entry - risk : entry + risk;
    }

    // -------------------------------------------------------------------------
    // Aggregation
    // -------------------------------------------------------------------------

    private IndexFuturesVolumeBacktestResult aggregate(IndexFuturesVolumeScanRequest req,
                                                        List<IndexFuturesVolumeTradeRecord> trades) {
        int wins = 0, losses = 0, openTrades = 0;
        double totalR = 0, grossProfit = 0, grossLoss = 0;
        double maxWinR = Double.MIN_VALUE, maxLossR = Double.MAX_VALUE;
        double sumWinR = 0, sumLossR = 0;
        int consecWins = 0, consecLosses = 0, maxCW = 0, maxCL = 0;
        double equity = 0, peak = 0, maxDrawdown = 0;

        for (IndexFuturesVolumeTradeRecord t : trades) {
            double r = t.getPnlR();
            totalR += r;
            equity += r;

            if (equity > peak) peak = equity;
            double dd = peak - equity;
            if (dd > maxDrawdown) maxDrawdown = dd;

            switch (t.getExitReason()) {
                case FULL_TARGET -> {
                    wins++; grossProfit += r; sumWinR += r;
                    if (r > maxWinR) maxWinR = r;
                    consecWins++; consecLosses = 0;
                    if (consecWins > maxCW) maxCW = consecWins;
                }
                case STOP_LOSS -> {
                    losses++; grossLoss += Math.abs(r); sumLossR += r;
                    if (r < maxLossR) maxLossR = r;
                    consecLosses++; consecWins = 0;
                    if (consecLosses > maxCL) maxCL = consecLosses;
                }
                default -> openTrades++;
            }
        }

        int closed = wins + losses;
        double winRate      = closed > 0 ? (double) wins / closed * 100.0 : 0;
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : grossProfit;
        double avgWinR      = wins   > 0 ? sumWinR  / wins   : 0;
        double avgLossR     = losses > 0 ? sumLossR / losses : 0;
        double avgPnlR      = closed > 0 ? totalR   / closed : 0;

        return IndexFuturesVolumeBacktestResult.builder()
                .label(req.getLabel())
                .instrumentKey(req.getInstrumentKey())
                .fromDate(req.getFromDate())
                .toDate(req.getToDate())
                .spikeMultiplier(req.getSpikeMultiplier())
                .climaxMultiplier(req.getClimaxMultiplier())
                .riskPercent(req.getRiskPercent())
                .breakoutRR(req.getBreakoutRR())
                .absorptionRR(req.getAbsorptionRR())
                .climaxRR(req.getClimaxRR())
                .totalTrades(trades.size())
                .wins(wins).losses(losses).openTrades(openTrades)
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
                .breakoutStats(buildTypeStat(trades, SignalType.BREAKOUT))
                .absorptionStats(buildTypeStat(trades, SignalType.ABSORPTION))
                .climaxStats(buildTypeStat(trades, SignalType.CLIMAX))
                .trades(trades)
                .build();
    }

    private SignalTypeStat buildTypeStat(List<IndexFuturesVolumeTradeRecord> trades, SignalType type) {
        List<IndexFuturesVolumeTradeRecord> sub = trades.stream()
                .filter(t -> t.getSignalType() == type).toList();
        int total = sub.size();
        if (total == 0) return SignalTypeStat.builder().type(type).total(0).wins(0).losses(0)
                .winRate(0).totalPnlR(0).avgPnlR(0).build();

        int w = (int) sub.stream().filter(t -> t.getExitReason() == ExitReason.FULL_TARGET).count();
        int l = (int) sub.stream().filter(t -> t.getExitReason() == ExitReason.STOP_LOSS).count();
        double sumR = sub.stream().mapToDouble(IndexFuturesVolumeTradeRecord::getPnlR).sum();
        int closed  = w + l;
        double wr   = closed > 0 ? (double) w / closed * 100.0 : 0;
        double avgR = closed > 0 ? sumR / closed : 0;

        return SignalTypeStat.builder()
                .type(type).total(total).wins(w).losses(l)
                .winRate(round(wr)).totalPnlR(round(sumR)).avgPnlR(round(avgR))
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private double average(List<Candle> candles) {
        return candles.stream().mapToLong(Candle::getVolume).average().orElse(0);
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private IndexFuturesVolumeBacktestResult emptyResult(IndexFuturesVolumeScanRequest req) {
        return IndexFuturesVolumeBacktestResult.builder()
                .label(req.getLabel())
                .instrumentKey(req.getInstrumentKey())
                .fromDate(req.getFromDate())
                .toDate(req.getToDate())
                .totalTrades(0)
                .trades(List.of())
                .build();
    }
}
