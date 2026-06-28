package com.trading.algo.service;

import com.trading.algo.discord.DiscordService;
import com.trading.algo.dtos.Candle;
import com.trading.algo.ipo.Ipo;
import com.trading.algo.ipo.IpoRepository;
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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class IpoStrategyMonitorService {

    private static final int IPO_UNIVERSE_DAYS = 365;
    private static final int CANDLE_LOOKBACK_DAYS = 120;
    private static final int WEEKLY_BREAKOUT_LOOKBACK = 4;
    private static final int REVERSAL_DOWNTREND_WEEKS = 4;

    private final IpoRepository ipoRepository;
    private final UpstoxInstrumentMasterService instrumentMasterService;
    private final UpstoxHistoricalCandleService candleService;
    private final TelegramService telegramService;
    private final DiscordService discordService;

    private final Map<String, LocalDate> lastAlertDateBySignal = new ConcurrentHashMap<>();

    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledScan() {
        scanAndAlert();
    }

    public void scanAndAlert() {
        LocalDate today = LocalDate.now();
        LocalDate fromListingDate = today.minusDays(IPO_UNIVERSE_DAYS);
        
        // Scan only the latest 1 year (365 days) of IPO stocks from the repository
        // This filters the IPO universe to only recent listings for strategy monitoring
        List<Ipo> ipos = ipoRepository.findByListingDateBetween(fromListingDate, today.minusDays(1));
        
        // OLD LOGIC - Scanning all stocks from IPO repository (commented out)
        // List<Ipo> ipos = ipoRepository.findAll();

        if (ipos.isEmpty()) {
            log.info("No past IPOs found for strategy monitoring");
            return;
        }

        List<IpoSignal> signals = new ArrayList<>();
        int skipped = 0;

        for (Ipo ipo : ipos) {
            if (ipo.getSymbol() == null || ipo.getSymbol().isBlank()) {
                skipped++;
                continue;
            }

            Optional<String> instrumentKey = instrumentMasterService.getInstrumentKey(ipo.getSymbol());
            if (instrumentKey.isEmpty()) {
                log.warn("IPO strategy skipped; instrument key not found for {}", ipo.getSymbol());
                skipped++;
                continue;
            }

            try {
                List<DailyBar> dailyBars = fetchDailyBars(instrumentKey.get(), ipo.getListingDate(), today);
                List<WeeklyBar> weeklyBars = toWeeklyBars(dailyBars);
                Optional<IpoSignal> signal = evaluate(ipo, weeklyBars);
                signal.ifPresent(signals::add);
            } catch (Exception e) {
                log.warn("IPO strategy scan failed for {}: {}", ipo.getSymbol(), e.getMessage());
                skipped++;
            }
        }

        List<IpoSignal> freshSignals = signals.stream()
                .filter(this::notAlertedToday)
                .sorted(Comparator.comparingInt(IpoSignal::priority).reversed()
                        .thenComparingDouble(IpoSignal::changePct).reversed())
                .toList();

         if (freshSignals.isEmpty()) {
             log.info("IPO strategy scan complete: no fresh signals, skipped={}", skipped);
             return;
         }

         freshSignals.forEach(this::markAlertedToday);
         String message = buildMessage(freshSignals, ipos.size(), skipped);
         telegramService.sendMessageToInvestmentPicks(message);
         discordService.sendMessage(buildDiscordMessage(freshSignals));
     }

    private Optional<IpoSignal> evaluate(Ipo ipo, List<WeeklyBar> weeklyBars) {
        if (weeklyBars.size() < WEEKLY_BREAKOUT_LOOKBACK + 1) {
            return Optional.empty();
        }

        WeeklyBar current = weeklyBars.get(weeklyBars.size() - 1);
        List<WeeklyBar> completed = weeklyBars.subList(0, weeklyBars.size() - 1);
        WeeklyBar previous = completed.get(completed.size() - 1);
        List<WeeklyBar> recent = completed.subList(
                Math.max(0, completed.size() - WEEKLY_BREAKOUT_LOOKBACK),
                completed.size());

        double rangeHigh = recent.stream().mapToDouble(WeeklyBar::high).max().orElse(previous.high());
        double rangeLow = recent.stream().mapToDouble(WeeklyBar::low).min().orElse(previous.low());
        double changePct = previous.close() > 0
                ? ((current.close() - previous.close()) / previous.close()) * 100.0
                : 0;

        boolean weeklyBreakout = current.close() > rangeHigh;
        boolean weeklyBreakdown = current.close() < rangeLow;
        boolean reversalBreakout = weeklyBreakout && isDowntrend(completed);

        if (reversalBreakout) {
            return Optional.of(new IpoSignal(
                    ipo.getName(),
                    ipo.getSymbol(),
                    "REVERSAL BREAKOUT",
                    3,
                    current.close(),
                    rangeHigh,
                    rangeLow,
                    changePct,
                    "Downtrend base broken on upside"
            ));
        }

        if (weeklyBreakout) {
            return Optional.of(new IpoSignal(
                    ipo.getName(),
                    ipo.getSymbol(),
                    "WEEKLY BREAKOUT",
                    2,
                    current.close(),
                    rangeHigh,
                    rangeLow,
                    changePct,
                    "Close above recent weekly range"
            ));
        }

        if (weeklyBreakdown) {
            return Optional.of(new IpoSignal(
                    ipo.getName(),
                    ipo.getSymbol(),
                    "WEEKLY BREAKDOWN",
                    1,
                    current.close(),
                    rangeHigh,
                    rangeLow,
                    changePct,
                    "Close below recent weekly support"
            ));
        }

        return Optional.empty();
    }

    private boolean isDowntrend(List<WeeklyBar> completedWeeks) {
        if (completedWeeks.size() < REVERSAL_DOWNTREND_WEEKS) {
            return false;
        }

        List<WeeklyBar> weeks = completedWeeks.subList(
                completedWeeks.size() - REVERSAL_DOWNTREND_WEEKS,
                completedWeeks.size());

        int lowerCloseCount = 0;
        int lowerHighCount = 0;

        for (int i = 1; i < weeks.size(); i++) {
            if (weeks.get(i).close() < weeks.get(i - 1).close()) {
                lowerCloseCount++;
            }
            if (weeks.get(i).high() <= weeks.get(i - 1).high()) {
                lowerHighCount++;
            }
        }

        double totalMovePct = weeks.get(0).close() > 0
                ? ((weeks.get(weeks.size() - 1).close() - weeks.get(0).close()) / weeks.get(0).close()) * 100.0
                : 0;

        return totalMovePct <= -8.0 || (lowerCloseCount >= 2 && lowerHighCount >= 2);
    }

    private List<DailyBar> fetchDailyBars(String instrumentKey, LocalDate listingDate, LocalDate today) {
        LocalDate from = max(listingDate, today.minusDays(CANDLE_LOOKBACK_DAYS));
        List<DailyBar> bars = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
            DayOfWeek dow = date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                continue;
            }

            List<Candle> candles = candleService.fetchDayCandles(instrumentKey, date);
            if (candles == null || candles.isEmpty()) {
                continue;
            }

            bars.add(toDailyBar(date, candles));
        }

        bars.sort(Comparator.comparing(DailyBar::date));
        return bars;
    }

    private DailyBar toDailyBar(LocalDate date, List<Candle> candles) {
        candles.sort(Comparator.comparing(Candle::getTimestamp));

        double open = candles.get(0).getOpen();
        double high = candles.stream().mapToDouble(Candle::getHigh).max().orElse(open);
        double low = candles.stream().mapToDouble(Candle::getLow).min().orElse(open);
        double close = candles.get(candles.size() - 1).getClose();
        long volume = candles.stream().mapToLong(Candle::getVolume).sum();

        return new DailyBar(date, open, high, low, close, volume);
    }

    private List<WeeklyBar> toWeeklyBars(List<DailyBar> dailyBars) {
        List<WeeklyBar> weeklyBars = new ArrayList<>();
        WeeklyAccumulator acc = null;

        for (DailyBar bar : dailyBars) {
            LocalDate weekStart = bar.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (acc == null || !acc.weekStart.equals(weekStart)) {
                if (acc != null) {
                    weeklyBars.add(acc.toWeeklyBar());
                }
                acc = new WeeklyAccumulator(weekStart, bar);
            } else {
                acc.add(bar);
            }
        }

        if (acc != null) {
            weeklyBars.add(acc.toWeeklyBar());
        }

        return weeklyBars;
    }

    private boolean notAlertedToday(IpoSignal signal) {
        return !LocalDate.now().equals(lastAlertDateBySignal.get(alertKey(signal)));
    }

    private void markAlertedToday(IpoSignal signal) {
        lastAlertDateBySignal.put(alertKey(signal), LocalDate.now());
    }

    private String alertKey(IpoSignal signal) {
        return signal.symbol() + "|" + signal.type();
    }

    private String buildMessage(List<IpoSignal> signals, int total, int skipped) {
        StringBuilder sb = new StringBuilder();
        sb.append("*Past IPO Strategy Signals*\n");
        sb.append("------------------------\n");
        sb.append("Universe: last ").append(IPO_UNIVERSE_DAYS).append(" days")
                .append(" | Scanned: ").append(total - skipped)
                .append(" | Skipped: ").append(skipped).append("\n\n");

        for (IpoSignal signal : signals) {
            sb.append("*").append(signal.type()).append("*\n");
            sb.append("`").append(signal.symbol()).append("` - ").append(signal.name()).append("\n");
            sb.append("Close: ").append(fmt(signal.close()))
                    .append(" | Change: ").append(signed(signal.changePct())).append("%\n");
            sb.append("Range: ").append(fmt(signal.rangeLow()))
                    .append(" - ").append(fmt(signal.rangeHigh())).append("\n");
            sb.append(signal.reason()).append("\n\n");
        }

        return sb.toString();
    }

    private String buildDiscordMessage(List<IpoSignal> signals) {
        StringBuilder sb = new StringBuilder();
        sb.append("Past IPO Strategy Signals:\n");
        for (IpoSignal signal : signals) {
            sb.append(signal.symbol()).append("\n");
        }
        return sb.toString();
    }

    private LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }

    private String signed(double value) {
        return String.format("%+.2f", value);
    }

    private record DailyBar(LocalDate date, double open, double high, double low, double close, long volume) {}

    private record WeeklyBar(LocalDate weekStart, double open, double high, double low, double close, long volume) {}

    private record IpoSignal(
            String name,
            String symbol,
            String type,
            int priority,
            double close,
            double rangeHigh,
            double rangeLow,
            double changePct,
            String reason
    ) {}

    private static class WeeklyAccumulator {
        private final LocalDate weekStart;
        private final double open;
        private double high;
        private double low;
        private double close;
        private long volume;

        private WeeklyAccumulator(LocalDate weekStart, DailyBar firstBar) {
            this.weekStart = weekStart;
            this.open = firstBar.open();
            this.high = firstBar.high();
            this.low = firstBar.low();
            this.close = firstBar.close();
            this.volume = firstBar.volume();
        }

        private void add(DailyBar bar) {
            this.high = Math.max(this.high, bar.high());
            this.low = Math.min(this.low, bar.low());
            this.close = bar.close();
            this.volume += bar.volume();
        }

        private WeeklyBar toWeeklyBar() {
            return new WeeklyBar(weekStart, open, high, low, close, volume);
        }
    }
}
