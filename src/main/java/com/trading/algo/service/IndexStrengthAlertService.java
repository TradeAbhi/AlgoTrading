package com.trading.algo.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.trading.algo.dtos.Candle;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexStrengthAlertService {

    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime FIRST_REVIEW_TIME = LocalTime.of(9, 31);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final UpstoxHistoricalCandleService candleService;
    private final TelegramService telegramService;

    private static final List<IndexDef> INDICES = List.of(
            new IndexDef("NIFTY 50", "NSE_INDEX|Nifty 50"),
            new IndexDef("BANK NIFTY", "NSE_INDEX|Nifty Bank"),
            new IndexDef("FIN NIFTY", "NSE_INDEX|Nifty Fin Service"),
            new IndexDef("NEXT 50", "NSE_INDEX|Nifty Next 50"),
            new IndexDef("MIDCAP 100", "NSE_INDEX|Nifty Midcap 100"),
            new IndexDef("SMALLCAP 100", "NSE_INDEX|Nifty Smallcap 100"),
            new IndexDef("AUTO", "NSE_INDEX|Nifty Auto"),
            new IndexDef("IT", "NSE_INDEX|Nifty IT"),
            new IndexDef("PHARMA", "NSE_INDEX|Nifty Pharma"),
            new IndexDef("FMCG", "NSE_INDEX|Nifty FMCG"),
            new IndexDef("METAL", "NSE_INDEX|Nifty Metal"),
            new IndexDef("REALTY", "NSE_INDEX|Nifty Realty"),
            new IndexDef("ENERGY", "NSE_INDEX|Nifty Energy"),
            new IndexDef("OIL GAS", "NSE_INDEX|Nifty Oil & Gas"),
            new IndexDef("HEALTHCARE", "NSE_INDEX|Nifty Healthcare Index"),
            new IndexDef("CONSUMER DURABLES", "NSE_INDEX|Nifty Consumer Durables"),
            new IndexDef("PSU BANK", "NSE_INDEX|Nifty PSU Bank"),
            new IndexDef("PRIVATE BANK", "NSE_INDEX|Nifty Private Bank"),
            new IndexDef("MEDIA", "NSE_INDEX|Nifty Media")
    );

    @Scheduled(cron = "0 31 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void openingRangeStrengthAlert() {
        sendIndexStrengthAlert("Opening Index Strength", false);
    }

    @Scheduled(cron = "0 45/15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void morningFollowUpAlert() {
        sendIndexStrengthAlert("Index Strength Follow-up", false);
    }

    @Scheduled(cron = "0 0/15 10-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void intradayFollowUpAlert() {
        LocalTime now = LocalTime.now();
        if (now.isAfter(MARKET_CLOSE)) {
            return;
        }
        sendIndexStrengthAlert("Index Strength Follow-up", false);
    }

    public void sendManualIndexStrengthAlert() {
        sendIndexStrengthAlert("Manual Index Strength", true);
    }

    private void sendIndexStrengthAlert(String title, boolean allowOutsideReviewWindow) {
        LocalTime now = LocalTime.now();
        if (!allowOutsideReviewWindow && (now.isBefore(FIRST_REVIEW_TIME) || now.isAfter(MARKET_CLOSE))) {
            log.info("Index strength alert skipped outside review window: {}", now);
            return;
        }

        List<IndexStrength> strengths = scanIndices(LocalDate.now());
        if (strengths.isEmpty()) {
            telegramService.sendMessage(title + "\nNo index candle data available yet.");
            return;
        }

        strengths.sort(Comparator.comparingInt(IndexStrength::score).reversed());
        telegramService.sendMessage(buildMessage(title, strengths, now));
    }

    private List<IndexStrength> scanIndices(LocalDate date) {
        List<IndexStrength> result = new ArrayList<>();

        for (IndexDef index : INDICES) {
            try {
                List<Candle> candles = candleService.fetchDayCandles(index.instrumentKey(), date);
                if (candles == null || candles.isEmpty()) {
                    log.debug("No candles available for {}", index.name());
                    continue;
                }

                Candle openingRange = firstCandleAtOrAfter(candles, MARKET_OPEN);
                Candle latest = candles.get(candles.size() - 1);
                if (openingRange == null || latest == null) {
                    continue;
                }

                result.add(evaluate(index, openingRange, latest));
            } catch (Exception e) {
                log.warn("Index strength scan failed for {}: {}", index.name(), e.getMessage());
            }
        }

        return result;
    }

    private IndexStrength evaluate(IndexDef index, Candle openingRange, Candle latest) {
        double range = openingRange.getHigh() - openingRange.getLow();
        double midpoint = (openingRange.getHigh() + openingRange.getLow()) / 2.0;
        double latestClose = latest.getClose();
        double dayChangePct = openingRange.getOpen() > 0
                ? ((latestClose - openingRange.getOpen()) / openingRange.getOpen()) * 100.0
                : 0;
        double closeLocation = range > 0
                ? ((openingRange.getClose() - openingRange.getLow()) / range) * 100.0
                : 50.0;

        String bias;
        int score;

        if (latestClose > openingRange.getHigh()) {
            bias = "BUYING BREAKOUT";
            score = 5;
        } else if (latestClose >= midpoint && openingRange.getClose() >= midpoint && closeLocation >= 65) {
            bias = "BUYERS HOLDING";
            score = 4;
        } else if (latestClose >= midpoint) {
            bias = "MILD BUYING";
            score = 3;
        } else if (latestClose < openingRange.getLow()) {
            bias = "SELLING BREAKDOWN";
            score = 0;
        } else if (latestClose < midpoint && openingRange.getClose() < midpoint && closeLocation <= 35) {
            bias = "SELLERS HOLDING";
            score = 1;
        } else {
            bias = "NEUTRAL";
            score = 2;
        }

        return new IndexStrength(
                index.name(),
                bias,
                score,
                latestClose,
                openingRange.getHigh(),
                openingRange.getLow(),
                midpoint,
                dayChangePct
        );
    }

    private String buildMessage(String title, List<IndexStrength> strengths, LocalTime now) {
        List<IndexStrength> leaders = strengths.stream()
                .filter(s -> s.score() >= 4)
                .limit(5)
                .toList();

        List<IndexStrength> weakest = strengths.stream()
                .sorted(Comparator.comparingInt(IndexStrength::score)
                        .thenComparing(IndexStrength::dayChangePct))
                .limit(5)
                .toList();

        long buying = strengths.stream().filter(s -> s.score() >= 3).count();
        long selling = strengths.stream().filter(s -> s.score() <= 1).count();
        String marketBias = buying > selling
                ? "Buying side stronger"
                : selling > buying ? "Selling side stronger" : "Mixed / neutral";

        StringBuilder sb = new StringBuilder();
        sb.append("*").append(title).append("* | ").append(now.format(TIME_FMT)).append("\n");
        sb.append("------------------------\n");
        sb.append("Bias: ").append(marketBias)
                .append(" (Buy ").append(buying)
                .append(" / Sell ").append(selling)
                .append(" / Total ").append(strengths.size()).append(")\n\n");

        sb.append("*Leaders*\n");
        appendRows(sb, leaders);

        sb.append("\n*Weakest*\n");
        appendRows(sb, weakest);

        sb.append("\n*All Indices*\n");
        appendRows(sb, strengths);

        return sb.toString();
    }

    private void appendRows(StringBuilder sb, List<IndexStrength> rows) {
        if (rows == null || rows.isEmpty()) {
            sb.append("_None_\n");
            return;
        }

        for (IndexStrength s : rows) {
            String marker = s.score() >= 4 ? "[BUY]" : s.score() <= 1 ? "[SELL]" : "[MID]";
            sb.append(String.format(
                    "`%-18s` %s %-18s %+.2f%% | LTP %.2f | OR %.2f-%.2f%n",
                    s.name(),
                    marker,
                    s.bias(),
                    s.dayChangePct(),
                    s.latestClose(),
                    s.rangeLow(),
                    s.rangeHigh()
            ));
        }
    }

    private Candle firstCandleAtOrAfter(List<Candle> candles, LocalTime time) {
        return candles.stream()
                .filter(c -> !c.getTimestamp().toLocalTime().isBefore(time))
                .findFirst()
                .orElse(candles.get(0));
    }

    private record IndexDef(String name, String instrumentKey) {}

    private record IndexStrength(
            String name,
            String bias,
            int score,
            double latestClose,
            double rangeHigh,
            double rangeLow,
            double midpoint,
            double dayChangePct
    ) {}
}
