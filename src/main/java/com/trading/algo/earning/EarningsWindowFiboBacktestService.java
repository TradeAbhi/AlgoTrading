package com.trading.algo.earning;

import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.fibostrategy.OpeningCandleStrategyService;
import com.trading.algo.repo.EarningsRepository;
import com.trading.algo.service.UniverseService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Replays the Earnings Window Fibonacci confirmation rules against historical candles. */
@Service
@RequiredArgsConstructor
public class EarningsWindowFiboBacktestService {

    private static final LocalTime C2_TIME = LocalTime.of(9, 30);
    private static final int PRE_EARNINGS_DAYS = 10;
    private static final int POST_EARNINGS_DAYS = 3;

    private final EarningsRepository earningsRepository;
    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final OpeningCandleStrategyService strategy;
    private final BacktestConfig config;

    public BacktestReport run(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from date must be on or before to date");
        }

        List<TriggeredTrade> trades = new ArrayList<>();
        int tradingDays = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (isWeekend(date)) {
                continue;
            }
            tradingDays++;
            List<String> symbols = earningsRepository.findSymbolsInEarningsWindow(
                    date.minusDays(PRE_EARNINGS_DAYS), date.plusDays(POST_EARNINGS_DAYS)).stream()
                    .filter(UniverseService.NIFTY_FNO_SYMBOLS::contains)
                    .distinct()
                    .toList();
            Map<String, String> instrumentKeys = instrumentMaster.resolveToInstrumentKeyMap(symbols);
            for (Map.Entry<String, String> entry : instrumentKeys.entrySet()) {
                Optional<TriggeredTrade> trade = evaluate(entry.getKey(), entry.getValue(), date);
                if (trade.isPresent()) {
                    trades.add(trade.get());
                }
            }
        }

        long winners = trades.stream().filter(t -> t.outcome() == Outcome.TARGET_HIT).count();
        long losers = trades.stream().filter(t -> t.outcome() == Outcome.STOP_LOSS).count();
        long eod = trades.stream().filter(t -> t.outcome() == Outcome.EOD_EXIT).count();
        double totalR = trades.stream().mapToDouble(TriggeredTrade::realizedR).sum();
        return new BacktestReport(from, to, tradingDays, trades.size(), winners, losers, eod, totalR, trades);
    }

    private Optional<TriggeredTrade> evaluate(String symbol, String instrumentKey, LocalDate date) {
        List<Candle> candles = candleService.fetchDayCandles(instrumentKey, date);
        Optional<BacktestTrade> setup = strategy.evaluate(symbol, date, candles);
        if (setup.isEmpty()) {
            return Optional.empty();
        }

        BacktestTrade signal = setup.get();
        double breakoutLevel = signal.getDirection() == BacktestTrade.Direction.BUY
                ? Math.max(signal.getC1High(), signal.getC2High())
                : Math.min(signal.getC1Low(), signal.getC2Low());
        Optional<Candle> triggerCandle = candles.stream()
                .filter(c -> c.getTimestamp().toLocalTime().isAfter(C2_TIME))
                .filter(c -> signal.getDirection() == BacktestTrade.Direction.BUY
                        ? c.getClose() > breakoutLevel : c.getClose() < breakoutLevel)
                .min(Comparator.comparing(Candle::getTimestamp));
        if (triggerCandle.isEmpty()) {
            return Optional.empty();
        }

        Candle trigger = triggerCandle.get();
        double entry = trigger.getClose();
        double marginFactor = config.getSlMarginPercent() / 100.0;
        double stopLoss = signal.getDirection() == BacktestTrade.Direction.BUY
                ? signal.getC2Low() * (1 - marginFactor)
                : signal.getC2High() * (1 + marginFactor);
        double risk = Math.abs(entry - stopLoss);
        double target = signal.getDirection() == BacktestTrade.Direction.BUY
                ? entry + risk * config.getTargetRR()
                : entry - risk * config.getTargetRR();
        Resolution resolution = resolve(signal.getDirection(), candles, trigger, entry, stopLoss, target, risk);
        return Optional.of(new TriggeredTrade(symbol, date, signal.getDirection(), trigger.getTimestamp().toLocalTime(),
                entry, breakoutLevel, stopLoss, target, risk, signal.getC1WickRatio(),
                resolution.outcome(), resolution.exitPrice(), resolution.realizedR()));
    }

    private Resolution resolve(BacktestTrade.Direction direction, List<Candle> candles, Candle trigger,
                               double entry, double stopLoss, double target, double risk) {
        Candle last = trigger;
        for (Candle candle : candles.stream().filter(c -> c.getTimestamp().isAfter(trigger.getTimestamp()))
                .sorted(Comparator.comparing(Candle::getTimestamp)).toList()) {
            last = candle;
            if (direction == BacktestTrade.Direction.BUY) {
                if (candle.getLow() <= stopLoss) return new Resolution(Outcome.STOP_LOSS, stopLoss, -1.0);
                if (candle.getHigh() >= target) return new Resolution(Outcome.TARGET_HIT, target, config.getTargetRR());
            } else {
                if (candle.getHigh() >= stopLoss) return new Resolution(Outcome.STOP_LOSS, stopLoss, -1.0);
                if (candle.getLow() <= target) return new Resolution(Outcome.TARGET_HIT, target, config.getTargetRR());
            }
        }
        double exit = last.getClose();
        double realizedR = direction == BacktestTrade.Direction.BUY ? (exit - entry) / risk : (entry - exit) / risk;
        return new Resolution(Outcome.EOD_EXIT, exit, realizedR);
    }

    private boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private record Resolution(Outcome outcome, double exitPrice, double realizedR) { }
    public enum Outcome { TARGET_HIT, STOP_LOSS, EOD_EXIT }
    public record TriggeredTrade(String symbol, LocalDate date, BacktestTrade.Direction direction, LocalTime triggerTime,
                                 double entry, double breakoutLevel, double stopLoss, double target, double risk,
                                 double wickRatio, Outcome outcome, double exitPrice, double realizedR) { }
    public record BacktestReport(LocalDate from, LocalDate to, int tradingDays, int triggeredCount,
                                 long winners, long losers, long eodExits, double totalR, List<TriggeredTrade> trades) { }
}
