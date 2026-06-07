package com.trading.algo.service;

import com.trading.algo.dtos.Candle;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeekHighWeeklyCloseBreakoutService {

    private final NseWeekHighService nseWeekHighService;
    private final UpstoxInstrumentMasterService instrumentMasterService;
    private final UpstoxHistoricalCandleService candleService;
    private final TelegramService telegramService;

    @Scheduled(cron = "0 5 16 * * FRI", zone = "Asia/Kolkata")
    public void scheduledScan() {
        // For scheduled Friday run, route daily-breakout alerts to Investment Picks bot
        List<String> symbols = nseWeekHighService.fetchWeekHighSymbols();
        if (symbols == null || symbols.isEmpty()) {
            log.info("No 52-week high symbols found for weekly close breakout scheduled scan");
            return;
        }
        processSymbols(symbols, true);
    }

    public void scanAndAlert() {
        List<String> symbols = nseWeekHighService.fetchWeekHighSymbols();
        if (symbols.isEmpty()) {
            log.info("No 52-week high symbols found for weekly close breakout scan");
            return;
        }
        processSymbols(symbols);
    }

    /**
     * Trigger a scan/alert run for the provided list of symbols (used by file upload).
     */
    public void scanAndAlertForSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            log.info("scanAndAlertForSymbols: empty symbol list");
            return;
        }
        // This entrypoint is used for manual upload runs (Friday after 4pm) —
        // route daily-breakout alerts to Investment Picks bot for these runs.
        processSymbols(symbols, true);
    }

    // Backwards-compatible helper for scheduled runs and other callers that want default routing
    private void processSymbols(List<String> symbols) {
        processSymbols(symbols, false);
    }

    private void processSymbols(List<String> symbols, boolean sendDailyToInvestmentPicks) {
        List<WeeklyCloseBreakout> breakouts = new ArrayList<>();
        List<DailyBreakout> dailyBreakouts = new ArrayList<>();
        Set<String> signalled = new HashSet<>();
        int skipped = 0;

        for (String symbol : symbols) {
            Optional<String> instrumentKey = instrumentMasterService.getInstrumentKey(symbol);
            if (instrumentKey.isEmpty()) {
                skipped++;
                log.warn("52-week high weekly scan skipped; instrument key not found for {}", symbol);
                continue;
            }

            List<Candle> weeklyCandles = candleService.fetchWeeklyCandles(
                    instrumentKey.get(),
                    LocalDate.now().minusWeeks(8),
                    LocalDate.now().plusDays(1) // include today's data so Friday close is reflected
            );

            if (weeklyCandles == null || weeklyCandles.size() < 2) {
                skipped++;
                continue;
            }

            weeklyCandles.sort(Comparator.comparing(Candle::getTimestamp));
            Candle previous = weeklyCandles.get(weeklyCandles.size() - 2);
            Candle current = weeklyCandles.get(weeklyCandles.size() - 1);

            // Debug: log weekly candle values for investigation when a symbol fails/ passes
            log.debug("{} - weeklyCandles={}, prevHigh={}, prevClose={}, currClose={}, currVolume={}",
                    symbol,
                    weeklyCandles.size(),
                    previous.getHigh(),
                    previous.getClose(),
                    current.getClose(),
                    current.getVolume());

            if (current.getClose() > previous.getHigh()) {
                double breakoutPct = ((current.getClose() - previous.getHigh()) / previous.getHigh()) * 100.0;
                double weeklyGainPct = previous.getClose() > 0
                        ? ((current.getClose() - previous.getClose()) / previous.getClose()) * 100.0
                        : 0;

                breakouts.add(new WeeklyCloseBreakout(
                        symbol,
                        current.getClose(),
                        previous.getHigh(),
                        breakoutPct,
                        weeklyGainPct,
                        current.getVolume()
                ));
                signalled.add(symbol);
            }

            // --- DAILY check: any day in the current week had close > previous week's high ---
            try {
                LocalDate startOfWeek = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                List<Candle> dailyCandles = candleService.fetchDailyCandles(
                        instrumentKey.get(), startOfWeek, LocalDate.now());
                if (dailyCandles != null && !dailyCandles.isEmpty() && weeklyCandles.size() >= 2) {
                    dailyCandles.sort(Comparator.comparing(Candle::getTimestamp));
                    Candle previousWeek = weeklyCandles.get(weeklyCandles.size() - 2);

                    for (Candle day : dailyCandles) {
                        try {
                            if (day.getClose() > previousWeek.getHigh() && !signalled.contains(symbol)) {
                                double pct = ((day.getClose() - previousWeek.getHigh()) / previousWeek.getHigh()) * 100.0;
                                dailyBreakouts.add(new DailyBreakout(symbol, day.getClose(), previousWeek.getHigh(), pct, day.getVolume()));
                                signalled.add(symbol);
                                log.info("Daily-breakout for {} on {}: close={} prevWeekHigh={} pct={:+.2f}% vol={}",
                                        symbol,
                                        day.getTimestamp(),
                                        day.getClose(),
                                        previousWeek.getHigh(),
                                        pct,
                                        day.getVolume());
                                break; // only one signal per symbol for daily rule
                            }
                        } catch (Exception inner) {
                            // ignore individual day parsing issues and continue
                            log.debug("Error evaluating daily candle for {}: {}", symbol, inner.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Daily candles check failed for {}: {}", symbol, e.getMessage());
            }
        }

        if (breakouts.isEmpty() && dailyBreakouts.isEmpty()) {
            log.info("52-week high weekly close breakout scan complete: no signals, skipped={}", skipped);
            return;
        }

        if (!breakouts.isEmpty()) {
            breakouts.sort(Comparator.comparingDouble(WeeklyCloseBreakout::breakoutPct).reversed());
            telegramService.sendMessageToInvestmentPicks(buildMessage(breakouts, symbols.size(), skipped));
        }

        if (!dailyBreakouts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("*Daily Close > Prev Week High Breakouts*\n");
            sb.append("------------------------\n");
            for (DailyBreakout d : dailyBreakouts) {
                sb.append("`").append(d.symbol()).append("` ")
                        .append("Close: ").append(fmt(d.close()))
                        .append(" | Prev Week High: ").append(fmt(d.prevHigh()))
                        .append(" | BO: ").append(signed(d.pct())).append("%")
                        .append(" | Vol: ").append(String.format("%,d", d.volume()))
                        .append("\n");
            }
            if (sendDailyToInvestmentPicks) {
                telegramService.sendMessageToInvestmentPicks(sb.toString());
            } else {
                telegramService.sendMessage(sb.toString());
            }
        }
    }

    private record DailyBreakout(String symbol, double close, double prevHigh, double pct, long volume) {}

    private String buildMessage(List<WeeklyCloseBreakout> breakouts, int total, int skipped) {
        StringBuilder sb = new StringBuilder();
        sb.append("*52W High Weekly Close Breakouts*\n");
        sb.append("------------------------\n");
        sb.append("Rule: weekly close > previous weekly high\n");
        sb.append("Candidates: ").append(total)
                .append(" | Signals: ").append(breakouts.size())
                .append(" | Skipped: ").append(skipped).append("\n\n");

        for (WeeklyCloseBreakout breakout : breakouts) {
            sb.append("`").append(breakout.symbol()).append("` ")
                    .append("Close: ").append(fmt(breakout.close()))
                    .append(" | Prev WH: ").append(fmt(breakout.previousWeeklyHigh()))
                    .append(" | BO: ").append(signed(breakout.breakoutPct())).append("%")
                    .append(" | Week: ").append(signed(breakout.weeklyGainPct())).append("%")
                    .append(" | Vol: ").append(String.format("%,d", breakout.volume()))
                    .append("\n");
        }

        return sb.toString();
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }

    private String signed(double value) {
        return String.format("%+.2f", value);
    }

    private record WeeklyCloseBreakout(
            String symbol,
            double close,
            double previousWeeklyHigh,
            double breakoutPct,
            double weeklyGainPct,
            long volume
    ) {}
}
