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
 * IPO Breakout Backtest Service
 *
 * Strategy rules:
 *  - First candle = listing day or first available candle after listing date
 *  - Entry when any subsequent candle closes above first candle high
 *  - Stop loss = low of breakout candle
 *  - Risk-reward = 1:3
 *  - Trail SL to entry price at 1:3 (book 50% quantity)
 *  - Exit remaining 50% at 1:6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoBacktestService {

    private static final int MAX_SCAN_DAYS = 30;       // Maximum days to scan from closeDate
    private static final double RISK_REWARD_1 = 3.0;   // First target at 1:3
    private static final double RISK_REWARD_2 = 6.0;   // Second target at 1:6

    private final IpoRepository ipoRepository;
    private final IpoBacktestTradeRepository backtestRepository;
    private final UpstoxInstrumentMasterService instrumentMasterService;
    private final UpstoxHistoricalCandleService candleService;

    /**
     * Runs backtest for all IPOs in the database
     */
    @Transactional
    public BacktestSummary runBacktestForAllIpos() {
        List<Ipo> ipos = ipoRepository.findAll();
        return runBacktestForIpos(ipos);
    }

    /**
     * Runs backtest for specific IPOs only
     */
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

        // Save all trades
        backtestRepository.saveAll(trades);

        log.info("Backtest complete - Processed: {}, Skipped: {}, Trades: {}", processed, skipped, trades.size());
        return buildSummary(trades, processed, skipped);
    }

    /**
     * Runs backtest for a specific IPO
     * Optimized to start from closeDate (issue end date) and fetch day-by-day
     */
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

        // Use listingDate as starting point, fallback to closeDate
        LocalDate startDate = ipo.getListingDate() != null ? ipo.getListingDate() : ipo.getCloseDate();
        if (startDate == null) {
            log.warn("No closeDate or listingDate for {}", ipo.getSymbol());
            return Optional.empty();
        }

        // If startDate is in the future, use today as starting point
        if (startDate.isAfter(LocalDate.now())) {
            log.warn("CloseDate {} is in the future for {}, using today as start", startDate, ipo.getSymbol());
            startDate = LocalDate.now();
        }

        log.info("Starting candle fetch for {} from {}", ipo.getSymbol(), startDate);

        // Fetch candles incrementally and check for breakout
        IncrementalBacktestResult result = fetchAndCheckBreakout(instrumentKey, ipo, startDate);

        if (result.firstCandle == null) {
            // No candles found at all
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

        // Process daily breakout if found
        IpoBacktestTrade trade;
        if (result.breakout.isPresent()) {
            BreakoutResult br = result.breakout.get();
            trade = processTrade(ipo, result.firstCandle, br, result.allCandles, false);
        } else {
            // No daily breakout
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

        // Process weekly breakout if found
        if (result.weeklyBreakout.isPresent()) {
            BreakoutResult weeklyBr = result.weeklyBreakout.get();
            IpoBacktestTrade weeklyTrade = processTrade(ipo, result.firstCandle, weeklyBr, result.allCandles, true);

            // Merge weekly results into main trade
            trade.setWeeklyBreakoutDate(weeklyTrade.getBreakoutDate());
            trade.setWeeklyBreakoutOpen(weeklyTrade.getBreakoutOpen());
            trade.setWeeklyBreakoutHigh(weeklyTrade.getBreakoutHigh());
            trade.setWeeklyBreakoutLow(weeklyTrade.getBreakoutLow());
            trade.setWeeklyBreakoutClose(weeklyTrade.getBreakoutClose());
            trade.setWeeklyEntryPrice(weeklyTrade.getEntryPrice());
            trade.setWeeklyStopLoss(weeklyTrade.getStopLoss());
            trade.setWeeklyTarget1(weeklyTrade.getTarget1());
            trade.setWeeklyTarget2(weeklyTrade.getTarget2());
            trade.setWeeklyRiskPoints(weeklyTrade.getRiskPoints());
            trade.setWeeklyReward1Points(weeklyTrade.getReward1Points());
            trade.setWeeklyReward2Points(weeklyTrade.getReward2Points());
            trade.setWeeklyOutcome(weeklyTrade.getOutcome());
            trade.setWeeklyExitPrice(weeklyTrade.getExitPrice());
            trade.setWeeklyPnlPoints(weeklyTrade.getPnlPoints());
            trade.setWeeklyPnlPercent(weeklyTrade.getPnlPercent());
            trade.setWeeklyActualRR(weeklyTrade.getActualRR());
            trade.setWeeklyExitTime(weeklyTrade.getExitTime());
            trade.setWeeklyExitReason(weeklyTrade.getExitReason());
            trade.setWeeklySlTrailedToBreakeven(weeklyTrade.isSlTrailedToBreakeven());
            trade.setWeeklyTrailTime(weeklyTrade.getTrailTime());
        } else {
            trade.setWeeklyOutcome(IpoBacktestTrade.Outcome.NO_BREAKOUT);
            trade.setWeeklyExitReason("No weekly breakout within " + MAX_SCAN_DAYS + " days");
        }

        return Optional.of(trade);
    }

    /**
     * Fetches candles incrementally from closeDate and checks for breakout
     * Stops fetching once breakout triggers or max days reached
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

                    // Set first candle if not yet set
                    if (firstCandle == null) {
                        firstCandle = dayCandles.get(0);
                        breakoutLevel = firstCandle.getHigh();
                        log.info("First candle found for {} on {}: High={}", ipo.getSymbol(), current, breakoutLevel);
                    }

                    // Check for breakout in newly added candles
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
                                        Optional.empty()  // weekly breakout not found yet
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
        // Check for weekly breakout even if no daily breakout
        Optional<BreakoutResult> weeklyBreakout = checkWeeklyBreakout(allCandles, firstCandle, ipo);
        return new IncrementalBacktestResult(allCandles, firstCandle, Optional.empty(), weeklyBreakout);
    }

    /**
     * Aggregates daily candles to weekly candles and checks for weekly breakout
     */
    private Optional<BreakoutResult> checkWeeklyBreakout(List<Candle> dailyCandles, Candle firstCandle, Ipo ipo) {
        if (dailyCandles.isEmpty() || firstCandle == null) {
            return Optional.empty();
        }

        List<Candle> weeklyCandles = aggregateToWeekly(dailyCandles);
        if (weeklyCandles.size() < 2) {
            return Optional.empty();
        }

        double breakoutLevel = firstCandle.getHigh();

        // Check for weekly breakout (skip first weekly candle which contains first daily candle)
        for (int i = 1; i < weeklyCandles.size(); i++) {
            Candle weeklyCandle = weeklyCandles.get(i);
            if (weeklyCandle.getClose() > breakoutLevel) {
                log.info("Weekly breakout found for {} on week {}: Close={} > Level={}",
                        ipo.getSymbol(), i, weeklyCandle.getClose(), breakoutLevel);
                // Map back to daily candles index
                int breakoutIndex = dailyCandles.indexOf(weeklyCandle);
                return Optional.of(new BreakoutResult(weeklyCandle, breakoutIndex));
            }
        }

        return Optional.empty();
    }

    /**
     * Aggregates daily candles to weekly candles
     */
    private List<Candle> aggregateToWeekly(List<Candle> dailyCandles) {
        List<Candle> weeklyCandles = new ArrayList<>();

        if (dailyCandles.isEmpty()) {
            return weeklyCandles;
        }

        List<Candle> currentWeek = new ArrayList<>();
        LocalDate currentWeekStart = null;

        for (Candle daily : dailyCandles) {
            LocalDate candleDate = daily.getTimestamp().toLocalDate();

            // Start new week
            if (currentWeekStart == null || candleDate.isAfter(currentWeekStart.plusDays(6))) {
                if (!currentWeek.isEmpty()) {
                    weeklyCandles.add(createWeeklyCandle(currentWeek));
                }
                currentWeek = new ArrayList<>();
                currentWeekStart = candleDate;
            }

            currentWeek.add(daily);
        }

        // Add last week
        if (!currentWeek.isEmpty()) {
            weeklyCandles.add(createWeeklyCandle(currentWeek));
        }

        return weeklyCandles;
    }

    /**
     * Creates a weekly candle from a list of daily candles
     */
    private Candle createWeeklyCandle(List<Candle> dailyCandles) {
        double open = dailyCandles.get(0).getOpen();
        double high = dailyCandles.stream().mapToDouble(Candle::getHigh).max().orElse(0);
        double low = dailyCandles.stream().mapToDouble(Candle::getLow).min().orElse(0);
        double close = dailyCandles.get(dailyCandles.size() - 1).getClose();
        long volume = dailyCandles.stream().mapToLong(Candle::getVolume).sum();
        LocalDateTime timestamp = dailyCandles.get(0).getTimestamp();

        return new Candle(timestamp, open, high, low, close, volume);
    }

    /**
     * Processes the trade after breakout
     * @param isWeekly if true, processes weekly trade, otherwise daily trade
     */
    private IpoBacktestTrade processTrade(Ipo ipo, Candle firstCandle, BreakoutResult breakout, List<Candle> allCandles, boolean isWeekly) {
        Candle breakoutCandle = breakout.breakoutCandle;
        int breakoutIndex = breakout.breakoutIndex;

        double entryPrice = breakoutCandle.getClose();
        double stopLoss = breakoutCandle.getLow();
        double riskPoints = entryPrice - stopLoss;
        double target1 = entryPrice + (riskPoints * RISK_REWARD_1);
        double target2 = entryPrice + (riskPoints * RISK_REWARD_2);

        // Simulate trade execution
        TradeResult result = simulateTrade(allCandles, breakoutIndex, entryPrice, stopLoss, target1, target2);

        // Assume 100 shares for backtest (50% booked at target1, 50% at target2)
        int totalQuantity = 100;
        int bookedQuantity = result.bookedAtTarget1 ? 50 : 0;
        int remainingQuantity = totalQuantity - bookedQuantity;

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
                .target1(target1)
                .target2(target2)
                .riskPoints(riskPoints)
                .reward1Points(target1 - entryPrice)
                .reward2Points(target2 - entryPrice)
                .totalQuantity(totalQuantity)
                .bookedQuantity(bookedQuantity)
                .remainingQuantity(remainingQuantity)
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
     * Simulates trade execution with trailing SL
     */
    private TradeResult simulateTrade(List<Candle> candles, int breakoutIndex,
                                       double entryPrice, double initialStopLoss,
                                       double target1, double target2) {
        double currentStopLoss = initialStopLoss;
        boolean target1Hit = false;
        boolean slTrailedToBreakeven = false;
        LocalDateTime trailTime = null;

        for (int i = breakoutIndex + 1; i < candles.size(); i++) {
            Candle c = candles.get(i);

            // Check if target1 hit
            if (!target1Hit && c.getHigh() >= target1) {
                target1Hit = true;
                slTrailedToBreakeven = true;
                currentStopLoss = entryPrice;  // Trail SL to entry
                trailTime = c.getTimestamp();
            }

            // Check if target2 hit (only if target1 already hit)
            if (target1Hit && c.getHigh() >= target2) {
                // Exit remaining 50% at target2
                double pnlPoints = (target1 - entryPrice) * 0.5 + (target2 - entryPrice) * 0.5;
                double pnlPercent = (pnlPoints / entryPrice) * 100;
                double actualRR = pnlPoints / (entryPrice - initialStopLoss);

                return new TradeResult(
                        IpoBacktestTrade.Outcome.TARGET2_HIT,
                        target2,
                        pnlPoints,
                        pnlPercent,
                        actualRR,
                        c.getTimestamp(),
                        "Target2 (1:6) hit",
                        slTrailedToBreakeven,
                        trailTime
                );
            }

            // Check if stop loss hit
            if (c.getLow() <= currentStopLoss) {
                double exitPrice = currentStopLoss;
                double pnlPoints;

                if (target1Hit) {
                    // 50% booked at target1, 50% stopped at trailed SL (breakeven)
                    pnlPoints = (target1 - entryPrice) * 0.5 + (exitPrice - entryPrice) * 0.5;
                } else {
                    // Full position stopped at initial SL
                    pnlPoints = exitPrice - entryPrice;
                }

                double pnlPercent = (pnlPoints / entryPrice) * 100;
                double actualRR = pnlPoints / (entryPrice - initialStopLoss);

                IpoBacktestTrade.Outcome outcome = target1Hit ?
                        IpoBacktestTrade.Outcome.SL_HIT_TRAILED :
                        IpoBacktestTrade.Outcome.SL_HIT;

                return new TradeResult(
                        outcome,
                        exitPrice,
                        pnlPoints,
                        pnlPercent,
                        actualRR,
                        c.getTimestamp(),
                        target1Hit ? "SL hit after trailing to breakeven" : "SL hit at initial level",
                        slTrailedToBreakeven,
                        trailTime
                );
            }
        }

        // EOD exit - neither target nor SL hit
        Candle lastCandle = candles.get(candles.size() - 1);
        double exitPrice = lastCandle.getClose();
        double pnlPoints;

        if (target1Hit) {
            // 50% booked at target1, 50% exited at EOD
            pnlPoints = (target1 - entryPrice) * 0.5 + (exitPrice - entryPrice) * 0.5;
        } else {
            // Full position exited at EOD
            pnlPoints = exitPrice - entryPrice;
        }

        double pnlPercent = (pnlPoints / entryPrice) * 100;
        double actualRR = pnlPoints / (entryPrice - initialStopLoss);

        return new TradeResult(
                IpoBacktestTrade.Outcome.EOD_EXIT,
                exitPrice,
                pnlPoints,
                pnlPercent,
                actualRR,
                lastCandle.getTimestamp(),
                "EOD exit - neither target nor SL hit",
                slTrailedToBreakeven,
                trailTime
        );
    }

    private BacktestSummary buildSummary(List<IpoBacktestTrade> trades, int processed, int skipped) {
        long wins = trades.stream().filter(t -> t.getOutcome() == IpoBacktestTrade.Outcome.TARGET1_HIT
                || t.getOutcome() == IpoBacktestTrade.Outcome.TARGET2_HIT).count();
        long losses = trades.stream().filter(t -> t.getOutcome() == IpoBacktestTrade.Outcome.SL_HIT
                || t.getOutcome() == IpoBacktestTrade.Outcome.SL_HIT_TRAILED).count();
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
        final Optional<BreakoutResult> weeklyBreakout;

        IncrementalBacktestResult(List<Candle> allCandles, Candle firstCandle,
                                 Optional<BreakoutResult> breakout,
                                 Optional<BreakoutResult> weeklyBreakout) {
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
        final boolean bookedAtTarget1;

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
            this.bookedAtTarget1 = slTrailedToBreakeven;
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
