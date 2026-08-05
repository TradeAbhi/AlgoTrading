package com.trading.algo.service;

import com.trading.algo.discord.DiscordService;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeekHighWeeklyCloseBreakoutService {

    private final NseWeekHighService nseWeekHighService;
    private final UpstoxInstrumentMasterService instrumentMasterService;
    private final UpstoxHistoricalCandleService candleService;
    private final TelegramService telegramService;
    private final DiscordService discordService;

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
                    LocalDate.now().minusWeeks(12),
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
                
                // Calculate body percentage: |close - open| / (high - low)
                double range = current.getHigh() - current.getLow();
                double bodyPct = range > 0 ? (Math.abs(current.getClose() - current.getOpen()) / range) * 100.0 : 0;

                // Momentum label: weekly close > the 10 completed candles before this week.
                // Missing history is reported separately and never removes the breakout signal.
                boolean hasTenWeeklyCandles = weeklyCandles.size() >= 11;
                List<Candle> previousTenWeeks = hasTenWeeklyCandles
                        ? weeklyCandles.subList(weeklyCandles.size() - 11, weeklyCandles.size() - 1)
                        : Collections.emptyList();
                boolean momentumPass = hasTenWeeklyCandles
                        && previousTenWeeks.stream().allMatch(week -> current.getClose() > week.getClose());

                log.debug("Weekly momentum check for {}: weeklyClose={} history={}/10 passed={}",
                        symbol, current.getClose(), previousTenWeeks.size(), momentumPass);

                breakouts.add(new WeeklyCloseBreakout(
                        symbol,
                        current.getClose(),
                        previous.getHigh(),
                        breakoutPct,
                        weeklyGainPct,
                        current.getVolume(),
                        bodyPct,
                        current.getOpen(),
                        current.getHigh(),
                        current.getLow(),
                        momentumPass,
                        hasTenWeeklyCandles
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
                                
                                // Calculate body percentage: |close - open| / (high - low)
                                double range = day.getHigh() - day.getLow();
                                double bodyPct = range > 0 ? (Math.abs(day.getClose() - day.getOpen()) / range) * 100.0 : 0;
                                
                                // Momentum check: daily close > last 10 daily closes (excluding this day)
                                boolean momentumPass = false;
                                try {
                                    List<Candle> last10DailyCandles = candleService.fetchDailyCandles(
                                            instrumentKey.get(),
                                            LocalDate.now().minusDays(15),
                                            LocalDate.now()
                                    );
                                    if (last10DailyCandles != null && last10DailyCandles.size() >= 11) {
                                        last10DailyCandles.sort(Comparator.comparing(Candle::getTimestamp));
                                        // Get last 10 candles before this breakout day
                                        int dayIndex = last10DailyCandles.indexOf(day);
                                        if (dayIndex >= 10) {
                                            List<Candle> previous10 = last10DailyCandles.subList(dayIndex - 10, dayIndex);
                                            momentumPass = previous10.stream()
                                                    .allMatch(d -> day.getClose() > d.getClose());
                                        }
                                    }
                                } catch (Exception e) {
                                    log.debug("Momentum check failed for daily breakout {}: {}", symbol, e.getMessage());
                                }
                                
                                dailyBreakouts.add(new DailyBreakout(symbol, day.getClose(), previousWeek.getHigh(), pct, day.getVolume(), 
                                        bodyPct, day.getOpen(), day.getHigh(), day.getLow(), momentumPass));
                                signalled.add(symbol);
                                log.info("Daily-breakout for {} on {}: close={} prevWeekHigh={} pct={:+.2f}% vol={} bodyPct={:.2f}% momentum={}",
                                        symbol,
                                        day.getTimestamp(),
                                        day.getClose(),
                                        previousWeek.getHigh(),
                                        pct,
                                        day.getVolume(),
                                        bodyPct,
                                        momentumPass);
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
            String message = buildMessage(breakouts, symbols.size(), skipped);
            telegramService.sendMessageToInvestmentPicks(message);
            discordService.sendMessage(buildDiscordMessage(breakouts));
        }

        if (!dailyBreakouts.isEmpty()) {
            // Categorize daily breakouts
            List<DailyBreakout> strongBody = dailyBreakouts.stream()
                    .filter(d -> d.bodyPct() >= 60.0)
                    .collect(Collectors.toList());
            
            List<DailyBreakout> weakBody = dailyBreakouts.stream()
                    .filter(d -> d.bodyPct() < 60.0)
                    .collect(Collectors.toList());
            
            List<DailyBreakout> bigMove = dailyBreakouts.stream()
                    .filter(d -> d.pct() > 10.0)
                    .collect(Collectors.toList());
            
            List<DailyBreakout> momentumPass = dailyBreakouts.stream()
                    .filter(d -> d.momentumPass())
                    .collect(Collectors.toList());
            
            List<DailyBreakout> momentumFail = dailyBreakouts.stream()
                    .filter(d -> !d.momentumPass())
                    .collect(Collectors.toList());

            StringBuilder sb = new StringBuilder();
            sb.append("*Daily Close > Prev Week High Breakouts*\n");
            sb.append("------------------------\n");
            
            // Category: Momentum Pass (daily close > last 10 daily closes)
            if (!momentumPass.isEmpty()) {
                sb.append("⚡ *Momentum Pass* (Close > Last 10 Days)\n");
                sb.append("Count: ").append(momentumPass.size()).append("\n");
                for (DailyBreakout d : momentumPass) {
                    sb.append("`").append(d.symbol()).append("` ")
                            .append("Close: ").append(fmt(d.close()))
                            .append(" | Prev Week High: ").append(fmt(d.prevHigh()))
                            .append(" | BO: ").append(signed(d.pct())).append("%")
                            .append(" | Body: ").append(String.format("%.1f", d.bodyPct())).append("%")
                            .append(" | Vol: ").append(String.format("%,d", d.volume()))
                            .append("\n");
                }
                sb.append("\n");
            }

            // Category: Momentum Fail (daily close <= some of last 10 daily closes)
            if (!momentumFail.isEmpty()) {
                sb.append("⚠️ *Momentum Fail* (Close <= Last 10 Days)\n");
                sb.append("Count: ").append(momentumFail.size()).append("\n");
                for (DailyBreakout d : momentumFail) {
                    sb.append("`").append(d.symbol()).append("` ")
                            .append("Close: ").append(fmt(d.close()))
                            .append(" | Prev Week High: ").append(fmt(d.prevHigh()))
                            .append(" | BO: ").append(signed(d.pct())).append("%")
                            .append(" | Body: ").append(String.format("%.1f", d.bodyPct())).append("%")
                            .append(" | Vol: ").append(String.format("%,d", d.volume()))
                            .append("\n");
                }
                sb.append("\n");
            }
            
            // Category: Strong Body (>= 60%)
            if (!strongBody.isEmpty()) {
                sb.append("💪 *Strong Body Breakouts* (Body >= 60%)\n");
                sb.append("Count: ").append(strongBody.size()).append("\n");
                for (DailyBreakout d : strongBody) {
                    sb.append("`").append(d.symbol()).append("` ")
                            .append("Close: ").append(fmt(d.close()))
                            .append(" | Prev Week High: ").append(fmt(d.prevHigh()))
                            .append(" | BO: ").append(signed(d.pct())).append("%")
                            .append(" | Body: ").append(String.format("%.1f", d.bodyPct())).append("%")
                            .append(" | Vol: ").append(String.format("%,d", d.volume()))
                            .append("\n");
                }
                sb.append("\n");
            }

            // Category: Weak Body (< 60%)
            if (!weakBody.isEmpty()) {
                sb.append("📉 *Weak Body Breakouts* (Body < 60%)\n");
                sb.append("Count: ").append(weakBody.size()).append("\n");
                for (DailyBreakout d : weakBody) {
                    sb.append("`").append(d.symbol()).append("` ")
                            .append("Close: ").append(fmt(d.close()))
                            .append(" | Prev Week High: ").append(fmt(d.prevHigh()))
                            .append(" | BO: ").append(signed(d.pct())).append("%")
                            .append(" | Body: ").append(String.format("%.1f", d.bodyPct())).append("%")
                            .append(" | Vol: ").append(String.format("%,d", d.volume()))
                            .append("\n");
                }
                sb.append("\n");
            }

            // Category: Big Move (> 10% breakout)
            if (!bigMove.isEmpty()) {
                sb.append("🚀 *Big Movers* (Breakout > 10%)\n");
                sb.append("Count: ").append(bigMove.size()).append("\n");
                for (DailyBreakout d : bigMove) {
                    sb.append("`").append(d.symbol()).append("` ")
                            .append("Close: ").append(fmt(d.close()))
                            .append(" | Prev Week High: ").append(fmt(d.prevHigh()))
                            .append(" | BO: ").append(signed(d.pct())).append("%")
                            .append(" | Body: ").append(String.format("%.1f", d.bodyPct())).append("%")
                            .append(" | Vol: ").append(String.format("%,d", d.volume()))
                            .append("\n");
                }
                sb.append("\n");
            }

            String dailyMessage = sb.toString();
            if (sendDailyToInvestmentPicks) {
                telegramService.sendMessageToInvestmentPicks(dailyMessage);
            } else {
                telegramService.sendMessage(dailyMessage);
            }
            discordService.sendMessage(buildDailyDiscordMessage(dailyBreakouts));
        }
    }

    private record DailyBreakout(String symbol, double close, double prevHigh, double pct, long volume, 
                                   double bodyPct, double open, double high, double low, boolean momentumPass) {}

    private String buildMessage(List<WeeklyCloseBreakout> breakouts, int total, int skipped) {
        // Categorize breakouts
        List<WeeklyCloseBreakout> strongBody = breakouts.stream()
                .filter(b -> b.bodyPct() >= 60.0)
                .collect(Collectors.toList());
        
        List<WeeklyCloseBreakout> weakBody = breakouts.stream()
                .filter(b -> b.bodyPct() < 60.0)
                .collect(Collectors.toList());
        
        List<WeeklyCloseBreakout> bigMove = breakouts.stream()
                .filter(b -> b.weeklyGainPct() > 10.0)
                .collect(Collectors.toList());
        
        List<WeeklyCloseBreakout> momentumPass = breakouts.stream()
                .filter(b -> b.hasTenWeeklyCandles() && b.momentumPass())
                .collect(Collectors.toList());
        
        List<WeeklyCloseBreakout> momentumFail = breakouts.stream()
                .filter(b -> b.hasTenWeeklyCandles() && !b.momentumPass())
                .collect(Collectors.toList());

        List<WeeklyCloseBreakout> missingWeeklyHistory = breakouts.stream()
                .filter(b -> !b.hasTenWeeklyCandles())
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("*52W High Weekly Close Breakouts*\n");
        sb.append("------------------------\n");
        sb.append("Rule: weekly close > previous weekly high\n");
        sb.append("Candidates: ").append(total)
                .append(" | Signals: ").append(breakouts.size())
                .append(" | Skipped: ").append(skipped).append("\n\n");

        // Category: Momentum Pass (weekly close > last 10 completed weekly closes)
        if (!momentumPass.isEmpty()) {
            sb.append("⚡ *Momentum Pass* (Weekly Close > Last 10 Weeks)\n");
            sb.append("Count: ").append(momentumPass.size()).append("\n");
            for (WeeklyCloseBreakout breakout : momentumPass) {
                sb.append("`").append(breakout.symbol()).append("` ")
                        .append("Close: ").append(fmt(breakout.close()))
                        .append(" | Prev WH: ").append(fmt(breakout.previousWeeklyHigh()))
                        .append(" | BO: ").append(signed(breakout.breakoutPct())).append("%")
                        .append(" | Week: ").append(signed(breakout.weeklyGainPct())).append("%")
                        .append(" | Body: ").append(String.format("%.1f", breakout.bodyPct())).append("%")
                        .append(" | Vol: ").append(String.format("%,d", breakout.volume()))
                        .append("\n");
            }
            sb.append("\n");
        }

        // Category: Momentum Fail (weekly close <= one or more of the last 10 completed weekly closes)
        if (!momentumFail.isEmpty()) {
            sb.append("⚠️ *Momentum Fail* (Weekly Close <= Last 10 Weeks)\n");
            sb.append("Count: ").append(momentumFail.size()).append("\n");
            for (WeeklyCloseBreakout breakout : momentumFail) {
                sb.append("`").append(breakout.symbol()).append("` ")
                        .append("Close: ").append(fmt(breakout.close()))
                        .append(" | Prev WH: ").append(fmt(breakout.previousWeeklyHigh()))
                        .append(" | BO: ").append(signed(breakout.breakoutPct())).append("%")
                        .append(" | Week: ").append(signed(breakout.weeklyGainPct())).append("%")
                        .append(" | Body: ").append(String.format("%.1f", breakout.bodyPct())).append("%")
                        .append(" | Vol: ").append(String.format("%,d", breakout.volume()))
                        .append("\n");
            }
            sb.append("\n");
        }

        // Signals remain visible when the data source did not return 10 prior weekly candles.
        if (!missingWeeklyHistory.isEmpty()) {
            sb.append("🗂️ *Weekly History Unavailable* (Less than 10 prior weekly candles)\n");
            sb.append("Count: ").append(missingWeeklyHistory.size()).append("\n");
            for (WeeklyCloseBreakout breakout : missingWeeklyHistory) {
                sb.append("`").append(breakout.symbol()).append("` ")
                        .append("Close: ").append(fmt(breakout.close()))
                        .append(" | Prev WH: ").append(fmt(breakout.previousWeeklyHigh()))
                        .append(" | BO: ").append(signed(breakout.breakoutPct())).append("%")
                        .append(" | Week: ").append(signed(breakout.weeklyGainPct())).append("%")
                        .append(" | Body: ").append(String.format("%.1f", breakout.bodyPct())).append("%")
                        .append(" | Vol: ").append(String.format("%,d", breakout.volume()))
                        .append("\n");
            }
            sb.append("\n");
        }

        // Category: Strong Body (>= 60%)
        if (!strongBody.isEmpty()) {
            sb.append("💪 *Strong Body Breakouts* (Body >= 60%)\n");
            sb.append("Count: ").append(strongBody.size()).append("\n");
            for (WeeklyCloseBreakout breakout : strongBody) {
                sb.append("`").append(breakout.symbol()).append("` ")
                        .append("Close: ").append(fmt(breakout.close()))
                        .append(" | Prev WH: ").append(fmt(breakout.previousWeeklyHigh()))
                        .append(" | BO: ").append(signed(breakout.breakoutPct())).append("%")
                        .append(" | Week: ").append(signed(breakout.weeklyGainPct())).append("%")
                        .append(" | Body: ").append(String.format("%.1f", breakout.bodyPct())).append("%")
                        .append(" | Vol: ").append(String.format("%,d", breakout.volume()))
                        .append("\n");
            }
            sb.append("\n");
        }

        // Category: Weak Body (< 60%)
        if (!weakBody.isEmpty()) {
            sb.append("📉 *Weak Body Breakouts* (Body < 60%)\n");
            sb.append("Count: ").append(weakBody.size()).append("\n");
            for (WeeklyCloseBreakout breakout : weakBody) {
                sb.append("`").append(breakout.symbol()).append("` ")
                        .append("Close: ").append(fmt(breakout.close()))
                        .append(" | Prev WH: ").append(fmt(breakout.previousWeeklyHigh()))
                        .append(" | BO: ").append(signed(breakout.breakoutPct())).append("%")
                        .append(" | Week: ").append(signed(breakout.weeklyGainPct())).append("%")
                        .append(" | Body: ").append(String.format("%.1f", breakout.bodyPct())).append("%")
                        .append(" | Vol: ").append(String.format("%,d", breakout.volume()))
                        .append("\n");
            }
            sb.append("\n");
        }

        // Category: Big Move (> 10% weekly gain)
        if (!bigMove.isEmpty()) {
            sb.append("🚀 *Big Movers* (Weekly Gain > 10%)\n");
            sb.append("Count: ").append(bigMove.size()).append("\n");
            for (WeeklyCloseBreakout breakout : bigMove) {
                sb.append("`").append(breakout.symbol()).append("` ")
                        .append("Close: ").append(fmt(breakout.close()))
                        .append(" | Prev WH: ").append(fmt(breakout.previousWeeklyHigh()))
                        .append(" | BO: ").append(signed(breakout.breakoutPct())).append("%")
                        .append(" | Week: ").append(signed(breakout.weeklyGainPct())).append("%")
                        .append(" | Body: ").append(String.format("%.1f", breakout.bodyPct())).append("%")
                        .append(" | Vol: ").append(String.format("%,d", breakout.volume()))
                        .append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildDiscordMessage(List<WeeklyCloseBreakout> breakouts) {
        StringBuilder sb = new StringBuilder();
        sb.append("52W High Weekly Close Breakouts:\n");
        for (WeeklyCloseBreakout breakout : breakouts) {
            sb.append(breakout.symbol()).append("\n");
        }
        return sb.toString();
    }

    private String buildDailyDiscordMessage(List<DailyBreakout> dailyBreakouts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Daily Close > Prev Week High Breakouts:\n");
        for (DailyBreakout d : dailyBreakouts) {
            sb.append(d.symbol()).append("\n");
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
            long volume,
            double bodyPct,
            double open,
            double high,
            double low,
            boolean momentumPass,
            boolean hasTenWeeklyCandles
    ) {}
}
