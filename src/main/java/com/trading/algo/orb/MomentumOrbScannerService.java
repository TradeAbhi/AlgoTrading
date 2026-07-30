package com.trading.algo.orb;

import com.trading.algo.dtos.Candle;
import com.trading.algo.dtos.OrbSymbolState;
import com.trading.algo.service.MarketSentimentService;
import com.trading.algo.service.MomentumStockSnapshotService;
import com.trading.algo.service.UniverseService;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs ORB only for symbols in the latest 15-minute momentum snapshot.
 * ORB signals are produced first and market breadth is applied afterwards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MomentumOrbScannerService {

    private static final LocalTime OPENING_CANDLE_TIME = LocalTime.of(9, 15);
    private static final LocalTime SCAN_START = LocalTime.of(9, 46);
    private static final LocalTime SCAN_END = LocalTime.of(15, 16);
    private static final double MIN_VOLUME_MULTIPLIER = 1.5;

    private final MomentumStockSnapshotService momentumStockSnapshotService;
    private final MarketSentimentService marketSentimentService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final UpstoxHistoricalCandleService candleService;
    private final OrbStateStore stateStore;
    private final TelegramService telegramService;
    private volatile LocalDate stateDate;

    @Scheduled(cron = "0 1,16,31,46 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanMomentumOrb() {
        scan(false);
    }

    /** Runs the same ORB flow on demand without the scheduler time guard. */
    public void triggerManualScan() {
        scan(true);
    }

    private void scan(boolean manual) {
        LocalTime now = LocalTime.now();
        if (!manual && (now.isBefore(SCAN_START) || now.isAfter(SCAN_END))) return;

        LocalDate today = LocalDate.now();
        resetStateForNewTradingDay(today);

        List<String> symbols = momentumStockSnapshotService.getLatestMomentumSymbols().stream()
                .filter(UniverseService.NIFTY_FNO_SYMBOLS::contains)
                .distinct()
                .collect(Collectors.toList());
        if (symbols.isEmpty()) {
            log.warn("[ORB] No momentum-stock snapshot available; skipping {} scan", manual ? "manual" : "scheduled");
            return;
        }

        Map<String, String> keys = instrumentMaster.resolveToInstrumentKeyMap(symbols);
        double adRatio = fetchAdRatio();
        int candidates = 0;
        int alerted = 0;
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            OrbSignal signal = evaluate(entry.getKey(), entry.getValue(), today);
            if (signal == null) continue;
            candidates++;

            if (!momentumStockSnapshotService.isTradeDirectionAllowed(adRatio, signal.direction())) {
                log.info("[ORB] {} {} blocked by A/D ratio {}", signal.symbol(), signal.direction(), adRatio);
                continue;
            }
            sendAlert(signal, adRatio);
            alerted++;
        }
        log.info("[ORB] momentum snapshot={} candidates={} A/D-approved={} ratio={}",
                symbols.size(), candidates, alerted, adRatio);
    }

    private OrbSignal evaluate(String symbol, String instrumentKey, LocalDate today) {
        try {
            List<Candle> candles = candleService.fetchDayCandles(instrumentKey, today);
            Candle opening = findCandle(candles, OPENING_CANDLE_TIME);
            Candle latest = latestClosedCandle(candles);
            if (opening == null || latest == null || latest.getTimestamp().toLocalTime().equals(OPENING_CANDLE_TIME)) {
                return null;
            }

            OrbSymbolState state = stateStore.get(symbol);
            if (state == null) {
                state = OrbSymbolState.builder()
                        .symbol(symbol).instrumentKey(instrumentKey)
                        .rollingHigh(opening.getHigh()).rollingLow(opening.getLow())
                        .openingCandleVolume(opening.getVolume())
                        .prevCandleHigh(opening.getHigh()).prevCandleLow(opening.getLow())
                        .openPrice(opening.getOpen()).build();
                stateStore.put(symbol, state);
            }

            if (latest.getVolume() < state.getOpeningCandleVolume() * MIN_VOLUME_MULTIPLIER) return null;
            if (!state.isBuyAlerted() && latest.getClose() > state.getRollingHigh()) {
                state.setBuyAlerted(true);
                return new OrbSignal(symbol, "BUY", latest.getClose(), state.getRollingHigh(), state.getPrevCandleLow(), latest.getTimestamp().toLocalTime());
            }
            if (!state.isSellAlerted() && latest.getClose() < state.getRollingLow()) {
                state.setSellAlerted(true);
                return new OrbSignal(symbol, "SELL", latest.getClose(), state.getRollingLow(), state.getPrevCandleHigh(), latest.getTimestamp().toLocalTime());
            }
            return null;
        } catch (Exception e) {
            log.warn("[ORB] {} could not be evaluated: {}", symbol, e.getMessage());
            return null;
        }
    }

    private synchronized void resetStateForNewTradingDay(LocalDate today) {
        if (!today.equals(stateDate)) {
            stateStore.clear();
            stateDate = today;
            log.info("[ORB] State reset for {}", today);
        }
    }

    private Candle findCandle(List<Candle> candles, LocalTime time) {
        return candles.stream().filter(c -> c.getTimestamp().toLocalTime().equals(time)).findFirst().orElse(null);
    }

    private Candle latestClosedCandle(List<Candle> candles) {
        return candles.stream().max(java.util.Comparator.comparing(Candle::getTimestamp)).orElse(null);
    }

    private double fetchAdRatio() {
        try {
            Map<String, Object> breadth = marketSentimentService.fetchAdvanceDeclineData();
            int advances = (int) breadth.getOrDefault("advances", 0);
            int declines = (int) breadth.getOrDefault("declines", 0);
            return declines > 0 ? (double) advances / declines : (advances > 0 ? advances : -1.0);
        } catch (Exception e) {
            log.warn("[ORB] A/D ratio unavailable: {}", e.getMessage());
            return -1.0;
        }
    }

    private void sendAlert(OrbSignal signal, double adRatio) {
        telegramService.sendMessageToIntraday(String.format(
                "*ORB %s — Momentum Snapshot*%n" +
                "Symbol: *%s*%nClose: %.2f%nBreakout level: %.2f%nStop reference: %.2f%n" +
                "Time: %s%nA/D ratio: %s",
                signal.direction(), signal.symbol(), signal.close(), signal.level(), signal.stopReference(), signal.time(),
                adRatio < 0 ? "unavailable" : String.format("%.2f", adRatio)));
    }

    private record OrbSignal(String symbol, String direction, double close, double level,
                             double stopReference, LocalTime time) { }
}
