package com.trading.algo.fibostrategy;

import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.service.UniverseService;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Scans all F&O stocks daily at 3:35 PM for strong daily candles.
 * A strong daily candle has body >= 60% of its total range.
 * Results are split into bullish (positive) and bearish (negative) movers
 * and sent via Telegram.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyStrongCandleScanner {

    private static final double BODY_PCT_THRESHOLD = 60.0;

    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final BacktestConfig config;
    private final TelegramService telegramService;

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanDailyStrongCandles() {
        scanForDate(LocalDate.now());
    }

    public Map<String, Object> scanStockBetweenDates(String symbol, LocalDate from, LocalDate to) {
        String key = instrumentMaster.getInstrumentKey(symbol).orElseThrow(
                () -> new IllegalArgumentException("Symbol not found: " + symbol));

        List<Candle> candles = candleService.fetchDailyCandles(key, from, to);
        log.info("scanStockBetweenDates {} from={} to={} — {} candles", symbol, from, to, candles.size());

        List<DailyCandle> bullish = new ArrayList<>();
        List<DailyCandle> bearish = new ArrayList<>();

        for (Candle day : candles) {
            double range = day.range();
            if (range <= 0) continue;

            double bodyPct   = day.body() / range * 100.0;
            double changePct = day.getOpen() > 0 ? (day.getClose() - day.getOpen()) / day.getOpen() * 100.0 : 0;

            DailyCandle dc = DailyCandle.builder()
                    .symbol(symbol)
                    .date(day.getTimestamp().toLocalDate())
                    .open(day.getOpen())
                    .high(day.getHigh())
                    .low(day.getLow())
                    .close(day.getClose())
                    .volume(day.getVolume())
                    .bodyPct(bodyPct)
                    .changePct(changePct)
                    .bullish(day.isBullish())
                    .build();

            if (bodyPct >= BODY_PCT_THRESHOLD) {
                if (day.isBullish()) bullish.add(dc);
                else                 bearish.add(dc);
            }
        }

        bullish.sort(Comparator.comparingDouble(DailyCandle::getBodyPct).reversed());
        bearish.sort(Comparator.comparingDouble(DailyCandle::getBodyPct).reversed());

        return Map.of(
                "symbol",        symbol,
                "from",          from.toString(),
                "to",            to.toString(),
                "totalScanned",  candles.size(),
                "bullishCount",  bullish.size(),
                "bearishCount",  bearish.size(),
                "bullish",       bullish,
                "bearish",       bearish
        );
    }

    public void scanForDate(LocalDate date) {
        log.info("DailyStrongCandleScanner — scanning {} FNO symbols for {}", UniverseService.NIFTY_FNO_SYMBOLS.size(), date);

        Map<String, String> symbolKeyMap = instrumentMaster.resolveToInstrumentKeyMap(UniverseService.NIFTY_FNO_SYMBOLS);

        List<DailyCandle> bullish = new ArrayList<>();
        List<DailyCandle> bearish = new ArrayList<>();

        for (Map.Entry<String, String> entry : symbolKeyMap.entrySet()) {
            String symbol = entry.getKey();
            String key    = entry.getValue();
            try {
                Thread.sleep(config.getApiDelayMs());

                List<Candle> candles = candleService.fetchDailyCandles(key, date, date);
                if (candles.isEmpty()) continue;

                Candle day = candles.get(0);
                double range = day.range();
                if (range <= 0) continue;

                double bodyPct = day.body() / range * 100.0;
                if (bodyPct < BODY_PCT_THRESHOLD) continue;

                double changePct = day.getOpen() > 0 ? (day.getClose() - day.getOpen()) / day.getOpen() * 100.0 : 0;

                DailyCandle dc = DailyCandle.builder()
                        .symbol(symbol)
                        .date(date)
                        .open(day.getOpen())
                        .high(day.getHigh())
                        .low(day.getLow())
                        .close(day.getClose())
                        .volume(day.getVolume())
                        .bodyPct(bodyPct)
                        .changePct(changePct)
                        .bullish(day.isBullish())
                        .build();

                if (day.isBullish()) bullish.add(dc);
                else                 bearish.add(dc);

                log.info("  {} {} body={}% change={}%", symbol, day.isBullish() ? "BULL" : "BEAR",
                        String.format("%.1f", bodyPct), String.format("%.2f", changePct));

            } catch (Exception e) {
                log.error("Error scanning {} on {}: {}", symbol, date, e.getMessage());
            }
        }

        bullish.sort(Comparator.comparingDouble(DailyCandle::getBodyPct).reversed());
        bearish.sort(Comparator.comparingDouble(DailyCandle::getBodyPct).reversed());

        log.info("Scan complete — bullish: {} bearish: {}", bullish.size(), bearish.size());

        if (!bullish.isEmpty() || !bearish.isEmpty()) {
            sendAlert(bullish, bearish, date);
        }
    }

    private void sendAlert(List<DailyCandle> bullish, List<DailyCandle> bearish, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Daily Strong Candle Report — ").append(date).append("*\n");
        sb.append("Body >= 60% of range | F&O Universe\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        if (!bullish.isEmpty()) {
            sb.append("🟢 *Bullish (").append(bullish.size()).append(")*\n");
            for (DailyCandle c : bullish) {
                sb.append(String.format("*%s* | O=%.2f H=%.2f L=%.2f C=%.2f | Body=%.1f%% | Chg=%+.2f%%\n",
                        c.getSymbol(), c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                        c.getBodyPct(), c.getChangePct()));
            }
            sb.append("\n");
        }

        if (!bearish.isEmpty()) {
            sb.append("🔴 *Bearish (").append(bearish.size()).append(")*\n");
            for (DailyCandle c : bearish) {
                sb.append(String.format("*%s* | O=%.2f H=%.2f L=%.2f C=%.2f | Body=%.1f%% | Chg=%+.2f%%\n",
                        c.getSymbol(), c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                        c.getBodyPct(), c.getChangePct()));
            }
        }

        telegramService.sendMessageToIntraday(sb.toString());
    }

    @Data
    @Builder
    private static class DailyCandle {
        private String    symbol;
        private LocalDate date;
        private double    open;
        private double    high;
        private double    low;
        private double    close;
        private long      volume;
        private double    bodyPct;
        private double    changePct;
        private boolean   bullish;
    }
}
