package com.trading.algo.fibostrategy;


import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.BacktestSummaryDTO;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade.Outcome;
import com.trading.algo.entity.IndexBacktestTrade;
import com.trading.algo.entity.IndexBacktestTrade.IndexName;
import com.trading.algo.repo.IndexBacktestTradeRepository;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
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
 * Runs the Opening Candle Strategy on index instruments:
 *   - Nifty 50      (NSE_INDEX|Nifty 50)
 *   - Bank Nifty    (NSE_INDEX|Nifty Bank)
 *   - Fin Nifty     (NSE_INDEX|Nifty Fin Service)
 *
 * Same strategy rules as F&O stocks — reuses OpeningCandleStrategyService.
 * Results stored in index_backtest_trade (separate from backtest_trade).
 *
 * NOTE: Indexes are cash-settled — no actual equity trade possible.
 * Results show signal quality only; actual trading uses Nifty/BankNifty futures or options.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexBacktestService {

    private final UpstoxHistoricalCandleService candleService;
    private final OpeningCandleStrategyService  strategy;
    private final IndexBacktestTradeRepository  tradeRepo;
    private final BacktestConfig                config;

    // =========================================================================
    // Run backtest for all indexes
    // =========================================================================

    @Transactional
    public List<BacktestSummaryDTO> runAll(LocalDate fromDate, LocalDate toDate, boolean clearOld) {
        List<BacktestSummaryDTO> results = new ArrayList<>();
        for (IndexName index : IndexName.values()) {
            results.add(run(index, fromDate, toDate, clearOld));
        }
        return results;
    }

    // =========================================================================
    // Run backtest for a single index
    // =========================================================================

    @Transactional
    public BacktestSummaryDTO run(IndexName indexName, LocalDate fromDate, LocalDate toDate, boolean clearOld) {
        log.info("Index backtest START — {} from={} to={}", indexName.displayName, fromDate, toDate);

        if (clearOld) {
            List<IndexBacktestTrade> old = tradeRepo
                    .findByIndexNameAndTradeDateBetweenOrderByTradeDateAsc(indexName, fromDate, toDate);
            tradeRepo.deleteAll(old);
            log.info("Cleared {} existing trades for {}", old.size(), indexName.displayName);
        }

        List<LocalDate> tradingDays = getTradingDays(fromDate, toDate);
        int signals = 0;

        for (LocalDate date : tradingDays) {
            if (tradeRepo.existsByIndexNameAndTradeDate(indexName, date)) {
                continue;
            }

            try {
                Thread.sleep(config.getApiDelayMs());

                log.info("{} {} — fetching candles using key: {}", 
                        indexName.displayName, date, indexName.instrumentKey);
                List<Candle> candles = candleService.fetchDayCandles(indexName.instrumentKey, date);
                log.info("{} {} — {} candles returned", indexName.displayName, date, candles.size());

                if (candles.isEmpty()) {
                    log.info("{} {} — NO CANDLES returned from Upstox", indexName.displayName, date);
                    continue;
                }

                // Reuse the same strategy service — same rules apply to indexes
                Optional<com.trading.algo.entity.BacktestTrade> result =
                        strategy.evaluate(indexName.name(), date, candles);

                if (result.isPresent()) {
                    com.trading.algo.entity.BacktestTrade t = result.get();
                    IndexBacktestTrade trade = IndexBacktestTrade.builder()
                            .indexName(indexName)
                            .tradeDate(date)
                            .direction(t.getDirection())
                            .c1Open(t.getC1Open()).c1High(t.getC1High())
                            .c1Low(t.getC1Low()).c1Close(t.getC1Close())
                            .c1WickRatio(t.getC1WickRatio())
                            .c2Open(t.getC2Open()).c2High(t.getC2High())
                            .c2Low(t.getC2Low()).c2Close(t.getC2Close())
                            .entryPrice(t.getEntryPrice())
                            .stopLoss(t.getStopLoss())
                            .target(t.getTarget())
                            .riskPoints(t.getRiskPoints())
                            .rewardPoints(t.getRewardPoints())
                            .outcome(t.getOutcome())
                            .exitPrice(t.getExitPrice())
                            .pnlPoints(t.getPnlPoints())
                            .pnlPercent(t.getPnlPercent())
                            .actualRR(t.getActualRR())
                            .exitCandleTime(t.getExitCandleTime())
                            .createdAt(LocalDateTime.now())
                            .build();

                    tradeRepo.save(trade);
                    signals++;
                    log.info("  ✅ {} {} {} → {} P&L={:.2f}pts",
                            indexName.displayName, date,
                            t.getDirection(), t.getOutcome(), t.getPnlPoints());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing {} on {}: {}", indexName.displayName, date, e.getMessage());
            }
        }

        log.info("Index backtest COMPLETE — {} signals={}", indexName.displayName, signals);
        return buildSummary(indexName, fromDate, toDate);
    }

    // =========================================================================
    // Summary
    // =========================================================================

    public BacktestSummaryDTO buildSummary(IndexName indexName, LocalDate fromDate, LocalDate toDate) {
        List<IndexBacktestTrade> trades = tradeRepo
                .findByIndexNameAndTradeDateBetweenOrderByTradeDateAsc(indexName, fromDate, toDate);

        if (trades.isEmpty()) {
            return BacktestSummaryDTO.builder()
                    .fromDate(fromDate).toDate(toDate)
                    .totalSignals(0).totalSymbolsScanned(1)
                    .build();
        }

        long wins     = trades.stream().filter(t -> t.getOutcome() == Outcome.TARGET_HIT).count();
        long losses   = trades.stream().filter(t -> t.getOutcome() == Outcome.SL_HIT).count();
        long eodExits = trades.stream().filter(t -> t.getOutcome() == Outcome.EOD_EXIT).count();

        double totalPnl = trades.stream().mapToDouble(IndexBacktestTrade::getPnlPoints).sum();

        double avgWin = trades.stream()
                .filter(t -> t.getOutcome() == Outcome.TARGET_HIT)
                .mapToDouble(IndexBacktestTrade::getPnlPoints)
                .average().orElse(0);

        double avgLoss = trades.stream()
                .filter(t -> t.getOutcome() == Outcome.SL_HIT)
                .mapToDouble(t -> Math.abs(t.getPnlPoints()))
                .average().orElse(0);

        double avgRR = trades.stream()
                .filter(t -> t.getOutcome() != Outcome.OPEN)
                .mapToDouble(IndexBacktestTrade::getActualRR)
                .average().orElse(0);

        double winRate    = (double) wins / trades.size() * 100.0;
        double lossRate   = 100.0 - winRate;
        double expectancy = (winRate / 100.0 * avgWin) - (lossRate / 100.0 * avgLoss);

        return BacktestSummaryDTO.builder()
                .fromDate(fromDate)
                .toDate(toDate)
                .totalSignals(trades.size())
                .totalWins((int) wins)
                .totalLosses((int) losses)
                .totalEodExits((int) eodExits)
                .winRate(winRate)
                .avgWinPoints(avgWin)
                .avgLossPoints(avgLoss)
                .avgRR(avgRR)
                .totalPnlPoints(totalPnl)
                .expectancy(expectancy)
                .totalSymbolsScanned(1)
                .totalSymbolsWithSignal(trades.isEmpty() ? 0 : 1)
                .build();
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private List<LocalDate> getTradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = from;
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
            d = d.plusDays(1);
        }
        return days;
    }
}
//import java.time.DayOfWeek;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Optional;
//
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import com.trading.algo.config.BacktestConfig;
//import com.trading.algo.dtos.BacktestSummaryDTO;
//import com.trading.algo.dtos.Candle;
//import com.trading.algo.entity.BacktestTrade.Outcome;
//import com.trading.algo.entity.IndexBacktestTrade;
//import com.trading.algo.entity.IndexBacktestTrade.IndexName;
//import com.trading.algo.repo.IndexBacktestTradeRepository;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//
///**
// * Runs the Opening Candle Strategy on index instruments:
// *   - Nifty 50      (NSE_INDEX|Nifty 50)
// *   - Bank Nifty    (NSE_INDEX|Nifty Bank)
// *   - Fin Nifty     (NSE_INDEX|Nifty Fin Service)
// *
// * Same strategy rules as F&O stocks — reuses OpeningCandleStrategyService.
// * Results stored in index_backtest_trade (separate from backtest_trade).
// *
// * NOTE: Indexes are cash-settled — no actual equity trade possible.
// * Results show signal quality only; actual trading uses Nifty/BankNifty futures or options.
// */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class IndexBacktestService {
//
//    private final UpstoxHistoricalCandleService candleService;
//    private final OpeningCandleStrategyService  strategy;
//    private final IndexBacktestTradeRepository  tradeRepo;
//    private final BacktestConfig                config;
//
//    // =========================================================================
//    // Run backtest for all indexes
//    // =========================================================================
//
//    @Transactional
//    public List<BacktestSummaryDTO> runAll(LocalDate fromDate, LocalDate toDate, boolean clearOld) {
//        List<BacktestSummaryDTO> results = new ArrayList<>();
//        for (IndexName index : IndexName.values()) {
//            results.add(run(index, fromDate, toDate, clearOld));
//        }
//        return results;
//    }
//
//    // =========================================================================
//    // Run backtest for a single index
//    // =========================================================================
//
//    @Transactional
//    public BacktestSummaryDTO run(IndexName indexName, LocalDate fromDate, LocalDate toDate, boolean clearOld) {
//        log.info("Index backtest START — {} from={} to={}", indexName.displayName, fromDate, toDate);
//
//        if (clearOld) {
//            List<IndexBacktestTrade> old = tradeRepo
//                    .findByIndexNameAndTradeDateBetweenOrderByTradeDateAsc(indexName, fromDate, toDate);
//            tradeRepo.deleteAll(old);
//            log.info("Cleared {} existing trades for {}", old.size(), indexName.displayName);
//        }
//
//        List<LocalDate> tradingDays = getTradingDays(fromDate, toDate);
//        int signals = 0;
//
//        for (LocalDate date : tradingDays) {
//            if (tradeRepo.existsByIndexNameAndTradeDate(indexName, date)) {
//                continue;
//            }
//
//            try {
//                Thread.sleep(config.getApiDelayMs());
//
//                List<Candle> candles = candleService.fetchDayCandles(indexName.instrumentKey, date);
//
//                if (candles.isEmpty()) {
//                    log.debug("{} {} — no candles", indexName.displayName, date);
//                    continue;
//                }
//
//                // Reuse the same strategy service — same rules apply to indexes
//                Optional<com.trading.algo.entity.BacktestTrade> result =
//                        strategy.evaluate(indexName.name(), date, candles);
//
//                if (result.isPresent()) {
//                    com.trading.algo.entity.BacktestTrade t = result.get();
//                    IndexBacktestTrade trade = IndexBacktestTrade.builder()
//                            .indexName(indexName)
//                            .tradeDate(date)
//                            .direction(t.getDirection())
//                            .c1Open(t.getC1Open()).c1High(t.getC1High())
//                            .c1Low(t.getC1Low()).c1Close(t.getC1Close())
//                            .c1WickRatio(t.getC1WickRatio())
//                            .c2Open(t.getC2Open()).c2High(t.getC2High())
//                            .c2Low(t.getC2Low()).c2Close(t.getC2Close())
//                            .entryPrice(t.getEntryPrice())
//                            .stopLoss(t.getStopLoss())
//                            .target(t.getTarget())
//                            .riskPoints(t.getRiskPoints())
//                            .rewardPoints(t.getRewardPoints())
//                            .outcome(t.getOutcome())
//                            .exitPrice(t.getExitPrice())
//                            .pnlPoints(t.getPnlPoints())
//                            .pnlPercent(t.getPnlPercent())
//                            .actualRR(t.getActualRR())
//                            .exitCandleTime(t.getExitCandleTime())
//                            .createdAt(LocalDateTime.now())
//                            .build();
//
//                    tradeRepo.save(trade);
//                    signals++;
//                    log.info("  ✅ {} {} {} → {} P&L={:.2f}pts",
//                            indexName.displayName, date,
//                            t.getDirection(), t.getOutcome(), t.getPnlPoints());
//                }
//
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                break;
//            } catch (Exception e) {
//                log.error("Error processing {} on {}: {}", indexName.displayName, date, e.getMessage());
//            }
//        }
//
//        log.info("Index backtest COMPLETE — {} signals={}", indexName.displayName, signals);
//        return buildSummary(indexName, fromDate, toDate);
//    }
//
//    // =========================================================================
//    // Summary
//    // =========================================================================
//
//    public BacktestSummaryDTO buildSummary(IndexName indexName, LocalDate fromDate, LocalDate toDate) {
//        List<IndexBacktestTrade> trades = tradeRepo
//                .findByIndexNameAndTradeDateBetweenOrderByTradeDateAsc(indexName, fromDate, toDate);
//
//        if (trades.isEmpty()) {
//            return BacktestSummaryDTO.builder()
//                    .fromDate(fromDate).toDate(toDate)
//                    .totalSignals(0).totalSymbolsScanned(1)
//                    .build();
//        }
//
//        long wins     = trades.stream().filter(t -> t.getOutcome() == Outcome.TARGET_HIT).count();
//        long losses   = trades.stream().filter(t -> t.getOutcome() == Outcome.SL_HIT).count();
//        long eodExits = trades.stream().filter(t -> t.getOutcome() == Outcome.EOD_EXIT).count();
//
//        double totalPnl = trades.stream().mapToDouble(IndexBacktestTrade::getPnlPoints).sum();
//
//        double avgWin = trades.stream()
//                .filter(t -> t.getOutcome() == Outcome.TARGET_HIT)
//                .mapToDouble(IndexBacktestTrade::getPnlPoints)
//                .average().orElse(0);
//
//        double avgLoss = trades.stream()
//                .filter(t -> t.getOutcome() == Outcome.SL_HIT)
//                .mapToDouble(t -> Math.abs(t.getPnlPoints()))
//                .average().orElse(0);
//
//        double avgRR = trades.stream()
//                .filter(t -> t.getOutcome() != Outcome.OPEN)
//                .mapToDouble(IndexBacktestTrade::getActualRR)
//                .average().orElse(0);
//
//        double winRate    = (double) wins / trades.size() * 100.0;
//        double lossRate   = 100.0 - winRate;
//        double expectancy = (winRate / 100.0 * avgWin) - (lossRate / 100.0 * avgLoss);
//
//        return BacktestSummaryDTO.builder()
//                .fromDate(fromDate)
//                .toDate(toDate)
//                .totalSignals(trades.size())
//                .totalWins((int) wins)
//                .totalLosses((int) losses)
//                .totalEodExits((int) eodExits)
//                .winRate(winRate)
//                .avgWinPoints(avgWin)
//                .avgLossPoints(avgLoss)
//                .avgRR(avgRR)
//                .totalPnlPoints(totalPnl)
//                .expectancy(expectancy)
//                .totalSymbolsScanned(1)
//                .totalSymbolsWithSignal(trades.isEmpty() ? 0 : 1)
//                .build();
//    }
//
//    // =========================================================================
//    // Helper
//    // =========================================================================
//
//    private List<LocalDate> getTradingDays(LocalDate from, LocalDate to) {
//        List<LocalDate> days = new ArrayList<>();
//        LocalDate d = from;
//        while (!d.isAfter(to)) {
//            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
//                days.add(d);
//            }
//            d = d.plusDays(1);
//        }
//        return days;
//    }
//}