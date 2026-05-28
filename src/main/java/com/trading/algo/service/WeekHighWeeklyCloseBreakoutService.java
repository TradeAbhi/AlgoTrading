package com.trading.algo.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.trading.algo.dtos.Candle;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
        scanAndAlert();
    }

    public void scanAndAlert() {
        List<String> symbols = nseWeekHighService.fetchWeekHighSymbols();
        if (symbols.isEmpty()) {
            log.info("No 52-week high symbols found for weekly close breakout scan");
            return;
        }

        List<WeeklyCloseBreakout> breakouts = new ArrayList<>();
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
                    LocalDate.now()
            );

            if (weeklyCandles.size() < 2) {
                skipped++;
                continue;
            }

            weeklyCandles.sort(Comparator.comparing(Candle::getTimestamp));
            Candle previous = weeklyCandles.get(weeklyCandles.size() - 2);
            Candle current = weeklyCandles.get(weeklyCandles.size() - 1);

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
            }
        }

        if (breakouts.isEmpty()) {
            log.info("52-week high weekly close breakout scan complete: no signals, skipped={}", skipped);
            return;
        }

        breakouts.sort(Comparator.comparingDouble(WeeklyCloseBreakout::breakoutPct).reversed());
        telegramService.sendMessage(buildMessage(breakouts, symbols.size(), skipped));
    }

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
