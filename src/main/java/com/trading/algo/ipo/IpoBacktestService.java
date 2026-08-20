package com.trading.algo.ipo;

import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.IpoBacktestTrade;
import com.trading.algo.repo.IpoBacktestTradeRepository;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * IPO / Weekly-High Breakout Backtest Service
 *
 * WEEKLY breakout rules (REPLACES the old "close > first candle high" check):
 *  1. Take the last 3 weekly candles INCLUDING the candidate breakout
 *     candle. Rise% = (highest high - lowest low) / lowest low * 100 across
 *     those 3 candles. Must be < RISE_THRESHOLD_PERCENT (25%).
 *  2. The candidate candle's CLOSE must be strictly greater than the
 *     highest high of the 10 weekly candles immediately BEFORE it.
 *  3. Post-breakout, monitor the next REJECTION_MONITOR_CANDLES (4) DAILY
 *     candles (ASSUMPTION #1 above) for two independent exit triggers:
 *
 *     a) SINGLE REJECTION: a candle with top-wick-ratio > 60% AND
 *        volume >= 75% of the breakout candle's volume -> exit
 *        immediately at that candle's close.
 *
 *     b) DUAL REJECTION: if, within the 4-candle window, there is at
 *        least one "upside rejection" candle (top-wick-ratio > 60%) AND
 *        at least one "downside rejection" candle (bottom-wick-ratio >
 *        60%) that did NOT already trigger rule (a) individually, sum
 *        the highest-volume candle from each side. If that sum is
 *        STRICTLY GREATER (ASSUMPTION #2) than breakout volume -> exit
 *        at the later of the two candles' close.
 *
 *     If neither triggers within the 4-candle window, fall back to the
 *     original SL/target (1:3 RR) simulation for the remaining holding
 *     period (ASSUMPTION #7).
 *
 * DAILY breakout path (unchanged - "close > first candle high", used for
 * the non-weekly / non-IPO-specific case) is left exactly as it was.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoBacktestService {

    // Bumped from 30 - the new weekly rule needs ~10 weeks of PRIOR history
    // before a breakout can even be evaluated, plus scan time after.
    // (ASSUMPTION #5 - tune as needed.)
    private static final int MAX_SCAN_DAYS = 150;

    private static final double RISK_REWARD = 3.0; // Target at 1:3 (daily path + weekly fallback)

    // ---- New weekly breakout rule constants ----
    private static final double RISE_THRESHOLD_PERCENT = 25.0;
    private static final int WEEKLY_LOOKBACK_FOR_HIGH = 10;
    private static final int REJECTION_MONITOR_CANDLES = 4;
    private static final double REJECTION_TOP_WICK_RATIO_THRESHOLD = 60.0;
    private static final double REJECTION_BOTTOM_WICK_RATIO_THRESHOLD = 60.0;
    private static final double REJECTION_VOLUME_PCT_OF_BREAKOUT = 0.75;

    private final IpoRepository ipoRepository;
    private final IpoBacktestTradeRepository backtestRepository;
    private final UpstoxInstrumentMasterService instrumentMasterService;
    private final UpstoxHistoricalCandleService candleService;

    @Transactional
    public BacktestSummary runBacktestForAllIpos() {
        List<Ipo> ipos = ipoRepository.findAll();
        return runBacktestForIpos(ipos);
    }

    @Transactional
    public BacktestSummary runBacktestForIpos(List<Ipo> ipos) {
        List<IpoBacktestTrade> trades = new ArrayList<>();
        int processed = 0;
        int skipped = 0;

        log.info("Starting backtest for {} IPOs", ipos.size());

        for (Ipo ipo : ipos) {
            log.info("Processing IPO: {} | Symbol: {} | CloseDate: {} | ListingDate: {}",
                    ipo.getName(), ipo.getSymbol(), ipo.getCloseDate(), ipo.getListingDate());

            if (ipo.getListingDate() == null) {
                log.warn("Skipping {} - no listing date", ipo.getSymbol());
                skipped++;
                continue;
            }

            if (ipo.getSymbol() == null || ipo.getSymbol().isBlank()) {
                log.warn("Skipping {} - no symbol", ipo.getName());
                skipped++;
                continue;
            }

            try {
                Optional<IpoBacktestTrade> trade = runBacktestForIpo(ipo);
                trade.ifPresent(trades::add);
                processed++;
                log.info("Completed backtest for {} - Outcome: {}", ipo.getSymbol(),
                        trade.map(t -> t.getOutcome().toString()).orElse("NO TRADE"));
            } catch (Exception e) {
                log.warn("Backtest failed for {}: {}", ipo.getSymbol(), e.getMessage());
                skipped++;
            }
        }

        backtestRepository.saveAll(trades);

        log.info("Backtest complete - Processed: {}, Skipped: {}, Trades: {}", processed, skipped, trades.size());
        return buildSummary(trades, processed, skipped);
    }

    @Transactional
    public Optional<IpoBacktestTrade> runBacktestForIpo(Ipo ipo) {
        if (ipo.getSymbol() == null || ipo.getSymbol().isBlank()) {
            return Optional.empty();
        }

        String instrumentKey = instrumentMasterService.getInstrumentKey(ipo.getSymbol())
                .orElse(null);

        if (instrumentKey == null) {
            log.warn("Instrument key not found for {}", ipo.getSymbol());
            return Optional.empty();
        }

        LocalDate startDate = ipo.getListingDate() != null ? ipo.getListingDate() : ipo.getCloseDate();
        if (startDate == null) {
            log.warn("No closeDate or listingDate for {}", ipo.getSymbol());
            return Optional.empty();
        }

        if (startDate.isAfter(LocalDate.now())) {
            log.warn("CloseDate {} is in the future for {}, using today as start", startDate, ipo.getSymbol());
            startDate = LocalDate.now();
        }

        log.info("Starting candle fetch for {} from {}", ipo.getSymbol(), startDate);

        IncrementalBacktestResult result = fetchAndCheckBreakout(instrumentKey, ipo, startDate);

        if (result.firstCandle == null) {
            IpoBacktestTrade noCandleTrade = IpoBacktestTrade.builder()
                    .symbol(ipo.getSymbol())
                    .companyName(ipo.getName())
                    .listingDate(ipo.getListingDate())
                    .tradeDate(startDate)
                    .outcome(IpoBacktestTrade.Outcome.NO_BREAKOUT)
                    .weeklyOutcome(IpoBacktestTrade.Outcome.NO_BREAKOUT)
                    .exitReason("No candles found within " + MAX_SCAN_DAYS + " days")
                    .weeklyExitReason("No candles found within " + MAX_SCAN_DAYS + " days")
                    .createdAt(LocalDateTime.now())
                    .build();
            return Optional.of(noCandleTrade);
        }

        IpoBacktestTrade trade;
        if (result.breakout.isPresent()) {
            BreakoutResult br = result.breakout.get();
            trade = processDailyTrade(ipo, result.firstCandle, br, result.allCandles);
        } else {
            trade = IpoBacktestTrade.builder()
                    .symbol(ipo.getSymbol())
                    .companyName(ipo.getName())
                    .listingDate(ipo.getListingDate())
                    .tradeDate(startDate)
                    .firstCandleDate(toLocalDate(result.firstCandle.getTimestamp()))
                    .firstCandleOpen(result.firstCandle.getOpen())
                    .firstCandleHigh(result.firstCandle.getHigh())
                    .firstCandleLow(result.firstCandle.getLow())
                    .firstCandleClose(result.firstCandle.getClose())
                    .outcome(IpoBacktestTrade.Outcome.NO_BREAKOUT)
                    .exitReason("No daily breakout within " + MAX_SCAN_DAYS + " days")
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        if (result.weeklyBreakout.isPresent()) {
            WeeklyBreakoutResult weeklyBr = result.weeklyBreakout.get();
            IpoBacktestTrade weeklyTrade = processWeeklyTrade(ipo, result.firstCandle, weeklyBr, result.allCandles);

            trade.setWeeklyBreakoutDate(weeklyTrade.getBreakoutDate());
            trade.setWeeklyBreakoutOpen(weeklyTrade.getBreakoutOpen());
            trade.setWeeklyBreakoutHigh(weeklyTrade.getBreakoutHigh());
            trade.setWeeklyBreakoutLow(weeklyTrade.getBreakoutLow());
            trade.setWeeklyBreakoutClose(weeklyTrade.getBreakoutClose());
            trade.setWeeklyEntryPrice(weeklyTrade.getEntryPrice());
            trade.setWeeklyStopLoss(weeklyTrade.getStopLoss());
            trade.setWeeklyTarget1(weeklyTrade.getTarget1());
            trade.setWeeklyTarget2(0.0);
            trade.setWeeklyRiskPoints(weeklyTrade.getRiskPoints());
            trade.setWeeklyReward1Points(weeklyTrade.getReward1Points());
            trade.setWeeklyReward2Points(0.0);
            trade.setWeeklyOutcome(weeklyTrade.getOutcome());
            trade.setWeeklyExitPrice(weeklyTrade.getExitPrice());
            trade.setWeeklyPnlPoints(weeklyTrade.getPnlPoints());
            trade.setWeeklyPnlPercent(weeklyTrade.getPnlPercent());
            trade.setWeeklyActualRR(weeklyTrade.getActualRR());
            trade.setWeeklyExitTime(weeklyTrade.getExitTime());
            trade.setWeeklyExitReason(weeklyTrade.getExitReason());
            trade.setWeeklySlTrailedToBreakeven(weeklyTrade.isSlTrailedToBreakeven());
            trade.setWeeklyTrailTime(weeklyTrade.getTrailTime());
            // ---- new fields - add to IpoBacktestTrade/@Builder ----
            trade.setWeeklyRiseFromBottomPercent(weeklyTrade.getWeeklyRiseFromBottomPercent());
            trade.setWeeklyHighestHighLast10(weeklyTrade.getWeeklyHighestHighLast10());
        } else {
            trade.setWeeklyOutcome(IpoBacktestTrade.Outcome.NO_BREAKOUT);
            trade.setWeeklyExitReason("No weekly breakout within " + MAX_SCAN_DAYS + " days");
        }

        return Optional.of(trade);
    }

    /**
     * Fetches candles incrementally from startDate. Daily breakout check is
     * UNCHANGED (close > first candle high). Weekly breakout check now uses
     * the new 25%-rise + 10-week-highest-close rule.
     */
    private IncrementalBacktestResult fetchAndCheckBreakout(String instrumentKey, Ipo ipo, LocalDate startDate) {
        List<Candle> allCandles = new ArrayList<>();
        Candle firstCandle = null;
        double breakoutLevel = 0;
        LocalDate current = startDate;
        int daysChecked = 0;

        while (daysChecked < MAX_SCAN_DAYS) {
            if (current.getDayOfWeek() == DayOfWeek.SATURDAY || current.getDayOfWeek() == DayOfWeek.SUNDAY) {
                current = current.plusDays(1);
                continue;
            }

            try {
                List<Candle> dayCandles = candleService.fetchDayCandles(instrumentKey, current);
                if (dayCandles != null && !dayCandles.isEmpty()) {
                    allCandles.addAll(dayCandles);

                    if (firstCandle == null) {
                        firstCandle = dayCandles.get(0);
                        breakoutLevel = firstCandle.getHigh();
                        log.info("First candle found for {} on {}: High={}", ipo.getSymbol(), current, breakoutLevel);
                    }

                    if (firstCandle != null) {
                        for (Candle c : dayCandles) {
                            if (c != firstCandle && c.getClose() > breakoutLevel) {
                                log.info("Daily breakout found for {} on {}: Close={} > Level={}",
                                        ipo.getSymbol(), current, c.getClose(), breakoutLevel);
                                int breakoutIndex = allCandles.indexOf(c);
                                return new IncrementalBacktestResult(
                                        allCandles,
                                        firstCandle,
                                        Optional.of(new BreakoutResult(c, breakoutIndex)),
                                        Optional.empty()
                                );
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to fetch candles for {} on {}", ipo.getSymbol(), current);
            }

            current = current.plusDays(1);
            daysChecked++;
        }

        log.info("No daily breakout found for {} after scanning {} days", ipo.getSymbol(), daysChecked);
        Optional<WeeklyBreakoutResult> weeklyBreakout = checkWeeklyBreakout(allCandles, ipo);
        return new IncrementalBacktestResult(allCandles, firstCandle, Optional.empty(), weeklyBreakout);
    }

    /**
     * NEW weekly breakout rule. Replaces the old fixed-level check.
     *
     * For each weekly candidate i (needs >= WEEKLY_LOOKBACK_FOR_HIGH prior
     * weekly candles, so i must be >= 10):
     *   1. 3-candle rise% (candidates i-2, i-1, i) must be < 25%.
     *   2. candidate.close must be > highest high of weekly candles [i-10, i-1].
     */
    private Optional<WeeklyBreakoutResult> checkWeeklyBreakout(List<Candle> dailyCandles, Ipo ipo) {
        if (dailyCandles.isEmpty()) {
            return Optional.empty();
        }

        List<WeeklyAggregate> weeklyAggregates = aggregateToWeekly(dailyCandles);

        int minRequiredIndex = WEEKLY_LOOKBACK_FOR_HIGH; // need 10 prior + current, so i starts at 10 (0-indexed)

        for (int i = minRequiredIndex; i < weeklyAggregates.size(); i++) {

            Candle candidate = weeklyAggregates.get(i).weeklyCandle();

            // ── Rule 1: 3-candle rise% (candidates i-2, i-1, i) < 25% ────
            Candle wMinus2 = weeklyAggregates.get(i - 2).weeklyCandle();
            Candle wMinus1 = weeklyAggregates.get(i - 1).weeklyCandle();

            double windowHigh = Math.max(candidate.getHigh(), Math.max(wMinus1.getHigh(), wMinus2.getHigh()));
            double windowLow = Math.min(candidate.getLow(), Math.min(wMinus1.getLow(), wMinus2.getLow()));

            if (windowLow <= 0) {
                continue; // guard against bad data
            }

            double risePercent = (windowHigh - windowLow) / windowLow * 100.0;

            if (risePercent >= RISE_THRESHOLD_PERCENT) {
                continue;
            }

            // ── Rule 2: candidate close > highest high of last 10 weekly candles BEFORE it ──
            double highestHighLast10 = 0;
            for (int j = i - WEEKLY_LOOKBACK_FOR_HIGH; j < i; j++) {
                highestHighLast10 = Math.max(highestHighLast10, weeklyAggregates.get(j).weeklyCandle().getHigh());
            }

            if (candidate.getClose() <= highestHighLast10) {
                continue;
            }

            log.info("Weekly breakout (new rule) found for {} at week starting {}: rise%={} close={} > 10wHigh={}",
                    ipo.getSymbol(), candidate.getTimestamp().toLocalDate(),
                    String.format("%.2f", risePercent),
                    String.format("%.2f", candidate.getClose()),
                    String.format("%.2f", highestHighLast10));

            // Map to the LAST DAILY candle belonging to this breakout week -
            // fixes the original dailyCandles.indexOf(weeklyCandle) bug,
            // since weeklyCandle is a synthesized object never present in
            // dailyCandles by reference/equals.
            List<Candle> memberDailyCandles = weeklyAggregates.get(i).memberDailyCandles();
            Candle lastDailyInBreakoutWeek = memberDailyCandles.get(memberDailyCandles.size() - 1);
            int dailyBreakoutIndex = dailyCandles.indexOf(lastDailyInBreakoutWeek);

            if (dailyBreakoutIndex < 0) {
                log.warn("Could not map weekly breakout back to daily index for {} - skipping", ipo.getSymbol());
                continue;
            }

            return Optional.of(new WeeklyBreakoutResult(
                    candidate, dailyBreakoutIndex, risePercent, highestHighLast10));
        }

        return Optional.empty();
    }

    /**
     * Aggregates daily candles to weekly candles, retaining each weekly
     * candle's member daily candles (needed to correctly map a weekly
     * breakout back to a daily index - see checkWeeklyBreakout above).
     */
    private List<WeeklyAggregate> aggregateToWeekly(List<Candle> dailyCandles) {
        List<WeeklyAggregate> weeklyAggregates = new ArrayList<>();

        if (dailyCandles.isEmpty()) {
            return weeklyAggregates;
        }

        List<Candle> currentWeek = new ArrayList<>();
        LocalDate currentWeekStart = null;

        for (Candle daily : dailyCandles) {
            LocalDate candleDate = daily.getTimestamp().toLocalDate();

            if (currentWeekStart == null || candleDate.isAfter(currentWeekStart.plusDays(6))) {
                if (!currentWeek.isEmpty()) {
                    weeklyAggregates.add(new WeeklyAggregate(
                            createWeeklyCandle(currentWeek), new ArrayList<>(currentWeek)));
                }
                currentWeek = new ArrayList<>();
                currentWeekStart = candleDate;
            }

            currentWeek.add(daily);
        }

        if (!currentWeek.isEmpty()) {
            weeklyAggregates.add(new WeeklyAggregate(
                    createWeeklyCandle(currentWeek), new ArrayList<>(currentWeek)));
        }

        return weeklyAggregates;
    }

    private Candle createWeeklyCandle(List<Candle> dailyCandles) {
        double open = dailyCandles.get(0).getOpen();
        double high = dailyCandles.stream().mapToDouble(Candle::getHigh).max().orElse(0);
        double low = dailyCandles.stream().mapToDouble(Candle::getLow).min().orElse(0);
        double close = dailyCandles.get(dailyCandles.size() - 1).getClose();
        long volume = dailyCandles.stream().mapToLong(Candle::getVolume).sum();
        LocalDateTime timestamp = dailyCandles.get(0).getTimestamp();

        return new Candle(timestamp, open, high, low, close, volume);
    }

    /** Daily breakout path - UNCHANGED, uses the original 1:3 RR simulateTrade. */
    private IpoBacktestTrade processDailyTrade(Ipo ipo, Candle firstCandle, BreakoutResult breakout, List<Candle> allCandles) {
        Candle breakoutCandle = breakout.breakoutCandle;
        int breakoutIndex = breakout.breakoutIndex;

        double entryPrice = breakoutCandle.getClose();
        double stopLoss = breakoutCandle.getLow();
        double riskPoints = entryPrice - stopLoss;
        double target = entryPrice + (riskPoints * RISK_REWARD);

        TradeResult result = simulateTrade(allCandles, breakoutIndex, entryPrice, stopLoss, target);

        int totalQuantity = 100;

        return IpoBacktestTrade.builder()
                .symbol(ipo.getSymbol())
                .companyName(ipo.getName())
                .listingDate(ipo.getListingDate())
                .tradeDate(toLocalDate(breakoutCandle.getTimestamp()))
                .firstCandleDate(toLocalDate(firstCandle.getTimestamp()))
                .firstCandleOpen(firstCandle.getOpen())
                .firstCandleHigh(firstCandle.getHigh())
                .firstCandleLow(firstCandle.getLow())
                .firstCandleClose(firstCandle.getClose())
                .breakoutDate(toLocalDate(breakoutCandle.getTimestamp()))
                .breakoutOpen(breakoutCandle.getOpen())
                .breakoutHigh(breakoutCandle.getHigh())
                .breakoutLow(breakoutCandle.getLow())
                .breakoutClose(breakoutCandle.getClose())
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .target1(target)
                .target2(0.0)
                .riskPoints(riskPoints)
                .reward1Points(target - entryPrice)
                .reward2Points(0.0)
                .totalQuantity(totalQuantity)
                .bookedQuantity(0)
                .remainingQuantity(totalQuantity)
                .outcome(result.outcome)
                .exitPrice(result.exitPrice)
                .pnlPoints(result.pnlPoints)
                .pnlPercent(result.pnlPercent)
                .actualRR(result.actualRR)
                .exitTime(result.exitTime)
                .exitReason(result.exitReason)
                .slTrailedToBreakeven(result.slTrailedToBreakeven)
                .trailTime(result.trailTime)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Weekly breakout path - NEW. Entry/SL logic unchanged (entry = close,
     * SL = breakout candle low, 1:3 RR target), but simulation now runs
     * through the new 4-daily-candle rejection-exit rules first, falling
     * back to standard SL/target simulation if neither rejection rule
     * fires within that window.
     */
    private IpoBacktestTrade processWeeklyTrade(Ipo ipo, Candle firstCandle, WeeklyBreakoutResult breakout, List<Candle> dailyCandles) {
        Candle breakoutCandle = breakout.breakoutCandle;
        int dailyBreakoutIndex = breakout.dailyBreakoutIndex;

        double entryPrice = breakoutCandle.getClose();
        double stopLoss = breakoutCandle.getLow();
        double riskPoints = entryPrice - stopLoss;
        double target = entryPrice + (riskPoints * RISK_REWARD);

        TradeResult result = simulateTradeWithRejectionLogic(
                dailyCandles, dailyBreakoutIndex, entryPrice, stopLoss, target, breakoutCandle.getVolume());

        int totalQuantity = 100;

        return IpoBacktestTrade.builder()
                .symbol(ipo.getSymbol())
                .companyName(ipo.getName())
                .listingDate(ipo.getListingDate())
                .tradeDate(toLocalDate(breakoutCandle.getTimestamp()))
                .firstCandleDate(toLocalDate(firstCandle.getTimestamp()))
                .firstCandleOpen(firstCandle.getOpen())
                .firstCandleHigh(firstCandle.getHigh())
                .firstCandleLow(firstCandle.getLow())
                .firstCandleClose(firstCandle.getClose())
                .breakoutDate(toLocalDate(breakoutCandle.getTimestamp()))
                .breakoutOpen(breakoutCandle.getOpen())
                .breakoutHigh(breakoutCandle.getHigh())
                .breakoutLow(breakoutCandle.getLow())
                .breakoutClose(breakoutCandle.getClose())
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .target1(target)
                .target2(0.0)
                .riskPoints(riskPoints)
                .reward1Points(target - entryPrice)
                .reward2Points(0.0)
                .totalQuantity(totalQuantity)
                .bookedQuantity(0)
                .remainingQuantity(totalQuantity)
                .outcome(result.outcome)
                .exitPrice(result.exitPrice)
                .pnlPoints(result.pnlPoints)
                .pnlPercent(result.pnlPercent)
                .actualRR(result.actualRR)
                .exitTime(result.exitTime)
                .exitReason(result.exitReason)
                .slTrailedToBreakeven(result.slTrailedToBreakeven)
                .trailTime(result.trailTime)
                .createdAt(LocalDateTime.now())
                // ---- new fields - add to IpoBacktestTrade/@Builder ----
                .weeklyRiseFromBottomPercent(breakout.risePercent)
                .weeklyHighestHighLast10(breakout.highestHighLast10)
                .build();
    }

    /**
     * Original SL/target simulation - kept unchanged for the daily
     * breakout path.
     */
    private TradeResult simulateTrade(List<Candle> candles, int breakoutIndex,
                                      double entryPrice, double initialStopLoss,
                                      double target) {
        double currentStopLoss = initialStopLoss;
        boolean targetHit = false;
        boolean slTrailedToBreakeven = false;
        LocalDateTime trailTime = null;

        for (int i = breakoutIndex + 1; i < candles.size(); i++) {
            Candle c = candles.get(i);

            if (!targetHit && c.getHigh() >= target) {
                targetHit = true;
                slTrailedToBreakeven = true;
                currentStopLoss = entryPrice;
                trailTime = c.getTimestamp();

                double pnlPoints = target - entryPrice;
                double pnlPercent = (pnlPoints / entryPrice) * 100;
                double actualRR = pnlPoints / (entryPrice - initialStopLoss);

                return new TradeResult(
                        IpoBacktestTrade.Outcome.TARGET1_HIT,
                        target, pnlPoints, pnlPercent, actualRR, c.getTimestamp(),
                        "Target (1:3) hit", slTrailedToBreakeven, trailTime);
            }

            if (c.getLow() <= currentStopLoss) {
                double exitPrice = currentStopLoss;
                double pnlPoints = exitPrice - entryPrice;
                double pnlPercent = (pnlPoints / entryPrice) * 100;
                double actualRR = pnlPoints / (entryPrice - initialStopLoss);

                IpoBacktestTrade.Outcome outcome = slTrailedToBreakeven ?
                        IpoBacktestTrade.Outcome.SL_HIT_TRAILED :
                        IpoBacktestTrade.Outcome.SL_HIT;

                return new TradeResult(
                        outcome, exitPrice, pnlPoints, pnlPercent, actualRR, c.getTimestamp(),
                        slTrailedToBreakeven ? "SL hit after trailing to entry" : "SL hit at initial level",
                        slTrailedToBreakeven, trailTime);
            }
        }

        Candle lastCandle = candles.get(candles.size() - 1);
        double exitPrice = lastCandle.getClose();
        double pnlPoints = exitPrice - entryPrice;
        double pnlPercent = (pnlPoints / entryPrice) * 100;
        double actualRR = pnlPoints / (entryPrice - initialStopLoss);

        return new TradeResult(
                IpoBacktestTrade.Outcome.EOD_EXIT, exitPrice, pnlPoints, pnlPercent, actualRR,
                lastCandle.getTimestamp(), "EOD exit - neither target nor SL hit",
                slTrailedToBreakeven, trailTime);
    }

    /**
     * NEW - weekly breakout simulation. Runs SL/target checks every candle
     * (same as original), PLUS - only for the first REJECTION_MONITOR_CANDLES
     * daily candles - the two rejection-based exit rules described above.
     * Falls back to plain SL/target simulation for any remaining candles
     * once the 4-candle window is exhausted without a rejection exit.
     */
    private TradeResult simulateTradeWithRejectionLogic(
            List<Candle> candles, int breakoutIndex,
            double entryPrice, double initialStopLoss, double target, long breakoutVolume) {

        double currentStopLoss = initialStopLoss;

        List<Candle> upsideRejectionCandidates = new ArrayList<>();   // top-wick > 60%
        List<Candle> downsideRejectionCandidates = new ArrayList<>(); // bottom-wick > 60%

        int monitored = 0;
        int i = breakoutIndex + 1;

        for (; i < candles.size() && monitored < REJECTION_MONITOR_CANDLES; i++, monitored++) {

            Candle c = candles.get(i);

            // ── Standard target/SL checks apply throughout, including
            // during the 4-candle rejection window. ──────────────────────
            if (c.getHigh() >= target) {
                double pnlPoints = target - entryPrice;
                double pnlPercent = (pnlPoints / entryPrice) * 100;
                double actualRR = pnlPoints / (entryPrice - initialStopLoss);
                return new TradeResult(
                        IpoBacktestTrade.Outcome.TARGET1_HIT, target, pnlPoints, pnlPercent, actualRR,
                        c.getTimestamp(), "Target (1:3) hit", true, c.getTimestamp());
            }

            if (c.getLow() <= currentStopLoss) {
                double exitPrice = currentStopLoss;
                double pnlPoints = exitPrice - entryPrice;
                double pnlPercent = (pnlPoints / entryPrice) * 100;
                double actualRR = pnlPoints / (entryPrice - initialStopLoss);
                return new TradeResult(
                        IpoBacktestTrade.Outcome.SL_HIT, exitPrice, pnlPoints, pnlPercent, actualRR,
                        c.getTimestamp(), "SL hit at initial level", false, null);
            }

            double range = c.getHigh() - c.getLow();
            if (range <= 0) {
                continue;
            }

            double topWickRatio = (c.getHigh() - Math.max(c.getOpen(), c.getClose())) / range * 100.0;
            double bottomWickRatio = (Math.min(c.getOpen(), c.getClose()) - c.getLow()) / range * 100.0;

            boolean isUpsideRejection = topWickRatio > REJECTION_TOP_WICK_RATIO_THRESHOLD;
            boolean isDownsideRejection = bottomWickRatio > REJECTION_BOTTOM_WICK_RATIO_THRESHOLD;

            // ── Rule (a): single-candle rejection, volume >= 75% of
            // breakout volume -> exit immediately. ────────────────────────
            if (isUpsideRejection && c.getVolume() >= breakoutVolume * REJECTION_VOLUME_PCT_OF_BREAKOUT) {

                double exitPrice = c.getClose();
                double pnlPoints = exitPrice - entryPrice;
                double pnlPercent = (pnlPoints / entryPrice) * 100;
                double actualRR = pnlPoints / (entryPrice - initialStopLoss);

                return new TradeResult(
                        IpoBacktestTrade.Outcome.REJECTION_VOLUME_EXIT, // NEW enum value - add to Outcome
                        exitPrice, pnlPoints, pnlPercent, actualRR, c.getTimestamp(),
                        String.format("Rejection exit: topWick=%.1f%% vol=%d (>=75%% of BO vol %d)",
                                topWickRatio, c.getVolume(), breakoutVolume),
                        false, null);
            }

            if (isUpsideRejection) {
                upsideRejectionCandidates.add(c);
            }
            if (isDownsideRejection) {
                downsideRejectionCandidates.add(c);
            }

            // ── Rule (b): dual rejection, combined volume > breakout volume
            // (ASSUMPTION #2: strict >) -> exit at the later candle's close. ──
            if (!upsideRejectionCandidates.isEmpty() && !downsideRejectionCandidates.isEmpty()) {

                Candle bestUpside = upsideRejectionCandidates.stream()
                        .max((a, b) -> Long.compare(a.getVolume(), b.getVolume())).orElseThrow();
                Candle bestDownside = downsideRejectionCandidates.stream()
                        .max((a, b) -> Long.compare(a.getVolume(), b.getVolume())).orElseThrow();

                long combinedVolume = bestUpside.getVolume() + bestDownside.getVolume();

                if (combinedVolume > breakoutVolume) {

                    Candle laterCandle = bestUpside.getTimestamp().isAfter(bestDownside.getTimestamp())
                            ? bestUpside : bestDownside;

                    double exitPrice = laterCandle.getClose();
                    double pnlPoints = exitPrice - entryPrice;
                    double pnlPercent = (pnlPoints / entryPrice) * 100;
                    double actualRR = pnlPoints / (entryPrice - initialStopLoss);

                    return new TradeResult(
                            IpoBacktestTrade.Outcome.COMBINED_REJECTION_EXIT, // NEW enum value - add to Outcome
                            exitPrice, pnlPoints, pnlPercent, actualRR, laterCandle.getTimestamp(),
                            String.format("Combined rejection exit: upVol=%d + downVol=%d = %d (> BO vol %d)",
                                    bestUpside.getVolume(), bestDownside.getVolume(), combinedVolume, breakoutVolume),
                            false, null);
                }
            }
        }

        // ── Neither rejection rule fired within the window - fall back to
        // plain SL/target simulation for the rest of the holding period. ──
        return simulateTrade(candles, i - 1, entryPrice, initialStopLoss, target);
    }

    private BacktestSummary buildSummary(List<IpoBacktestTrade> trades, int processed, int skipped) {
        long wins = trades.stream().filter(t -> t.getOutcome() == IpoBacktestTrade.Outcome.TARGET1_HIT).count();
        long losses = trades.stream().filter(t -> t.getOutcome() == IpoBacktestTrade.Outcome.SL_HIT
                || t.getOutcome() == IpoBacktestTrade.Outcome.SL_HIT_TRAILED
                || t.getOutcome() == IpoBacktestTrade.Outcome.REJECTION_VOLUME_EXIT
                || t.getOutcome() == IpoBacktestTrade.Outcome.COMBINED_REJECTION_EXIT).count();
        long noBreakouts = trades.stream().filter(t -> t.getOutcome() == IpoBacktestTrade.Outcome.NO_BREAKOUT).count();
        long eodExits = trades.stream().filter(t -> t.getOutcome() == IpoBacktestTrade.Outcome.EOD_EXIT).count();

        double totalPnl = trades.stream().mapToDouble(IpoBacktestTrade::getPnlPoints).sum();
        double avgPnl = trades.isEmpty() ? 0 : totalPnl / trades.size();
        double winRate = processed > 0 ? (wins * 100.0 / processed) : 0;

        return new BacktestSummary(processed, skipped, wins, losses, noBreakouts, eodExits, winRate, avgPnl, totalPnl);
    }

    private LocalDate toLocalDate(LocalDateTime dateTime) {
        return dateTime.toLocalDate();
    }

    // Inner classes

    private static class IncrementalBacktestResult {
        final List<Candle> allCandles;
        final Candle firstCandle;
        final Optional<BreakoutResult> breakout;
        final Optional<WeeklyBreakoutResult> weeklyBreakout;

        IncrementalBacktestResult(List<Candle> allCandles, Candle firstCandle,
                                  Optional<BreakoutResult> breakout,
                                  Optional<WeeklyBreakoutResult> weeklyBreakout) {
            this.allCandles = allCandles;
            this.firstCandle = firstCandle;
            this.breakout = breakout;
            this.weeklyBreakout = weeklyBreakout;
        }
    }

    private static class BreakoutResult {
        final Candle breakoutCandle;
        final int breakoutIndex;

        BreakoutResult(Candle breakoutCandle, int breakoutIndex) {
            this.breakoutCandle = breakoutCandle;
            this.breakoutIndex = breakoutIndex;
        }
    }

    /** NEW - carries the extra diagnostic fields the new rule computes. */
    private static class WeeklyBreakoutResult {
        final Candle breakoutCandle;
        final int dailyBreakoutIndex;
        final double risePercent;
        final double highestHighLast10;

        WeeklyBreakoutResult(Candle breakoutCandle, int dailyBreakoutIndex,
                             double risePercent, double highestHighLast10) {
            this.breakoutCandle = breakoutCandle;
            this.dailyBreakoutIndex = dailyBreakoutIndex;
            this.risePercent = risePercent;
            this.highestHighLast10 = highestHighLast10;
        }
    }

    /** NEW - pairs a weekly candle with its member daily candles, fixing the indexOf bug. */
    private record WeeklyAggregate(Candle weeklyCandle, List<Candle> memberDailyCandles) { }

    private static class TradeResult {
        final IpoBacktestTrade.Outcome outcome;
        final double exitPrice;
        final double pnlPoints;
        final double pnlPercent;
        final double actualRR;
        final LocalDateTime exitTime;
        final String exitReason;
        final boolean slTrailedToBreakeven;
        final LocalDateTime trailTime;

        TradeResult(IpoBacktestTrade.Outcome outcome, double exitPrice, double pnlPoints,
                    double pnlPercent, double actualRR, LocalDateTime exitTime, String exitReason,
                    boolean slTrailedToBreakeven, LocalDateTime trailTime) {
            this.outcome = outcome;
            this.exitPrice = exitPrice;
            this.pnlPoints = pnlPoints;
            this.pnlPercent = pnlPercent;
            this.actualRR = actualRR;
            this.exitTime = exitTime;
            this.exitReason = exitReason;
            this.slTrailedToBreakeven = slTrailedToBreakeven;
            this.trailTime = trailTime;
        }
    }

    public static class BacktestSummary {
        public final int processed;
        public final int skipped;
        public final long wins;
        public final long losses;
        public final long noBreakouts;
        public final long eodExits;
        public final double winRate;
        public final double avgPnl;
        public final double totalPnl;

        public BacktestSummary(int processed, int skipped, long wins, long losses,
                               long noBreakouts, long eodExits, double winRate,
                               double avgPnl, double totalPnl) {
            this.processed = processed;
            this.skipped = skipped;
            this.wins = wins;
            this.losses = losses;
            this.noBreakouts = noBreakouts;
            this.eodExits = eodExits;
            this.winRate = winRate;
            this.avgPnl = avgPnl;
            this.totalPnl = totalPnl;
        }
    }
}