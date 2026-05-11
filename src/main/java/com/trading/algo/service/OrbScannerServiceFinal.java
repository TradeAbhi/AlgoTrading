package com.trading.algo.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.trading.algo.config.OrbConfig;
import com.trading.algo.dtos.OrbCandle;
import com.trading.algo.dtos.OrbSymbolState;

@Service
public class OrbScannerServiceFinal {

    private static final Logger log = LoggerFactory.getLogger(OrbScannerServiceFinal.class);

    private final OrbConfig orbConfig;
    private final OrbStateStore stateStore;
    private final TelegramService telegramService;
    private final RestTemplate restTemplate;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final LocalTime SCAN_START = LocalTime.of(9, 46);
    private static final LocalTime SCAN_END   = LocalTime.of(15, 16);

    public OrbScannerServiceFinal(OrbConfig orbConfig,
                                  OrbStateStore stateStore,
                                  TelegramService telegramService,
                                  RestTemplate restTemplate) {
        this.orbConfig       = orbConfig;
        this.stateStore      = stateStore;
        this.telegramService = telegramService;
        this.restTemplate    = restTemplate;
    }

    // ── 9:15 AM: seed rolling levels from first candle ──────────────────────
    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureOpeningCandle() {
        log.info("[ORB] Capturing 9:15 opening candles...");
        stateStore.clear();

        Map<String, String> keyToSymbol = orbConfig.getKeyToSymbolMap();

        for (Map.Entry<String, String> entry : keyToSymbol.entrySet()) {
            String instrumentKey = entry.getKey();
            String symbol        = entry.getValue();
            try {
                OrbCandle candle = fetchLatestMinuteCandle(symbol, instrumentKey);
                if (candle == null) continue;

                stateStore.put(symbol, OrbSymbolState.builder()
                    .symbol(symbol)
                    .instrumentKey(instrumentKey)
                    .rollingHigh(candle.getHigh())
                    .rollingLow(candle.getLow())
                    .buyAlerted(false)
                    .sellAlerted(false)
                    .build());

                log.debug("[ORB] Seeded {} H:{} L:{}", symbol, candle.getHigh(), candle.getLow());
            } catch (Exception e) {
                log.error("[ORB] captureOpeningCandle error for {} ({}): {}", symbol, instrumentKey, e.getMessage());
            }
        }
        log.info("[ORB] Seeded {} symbols. First scan at 9:46.", stateStore.size());
    }

    // ── 9:46, 10:01, 10:16 ... 15:01, 15:16 ────────────────────────────────
    @Scheduled(cron = "0 1,16,31,46 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanBreakouts() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(SCAN_START) || now.isAfter(SCAN_END)) return;

        log.info("[ORB] Scan at {}, watching {} symbols", now, stateStore.size());
        int buyFired = 0, sellFired = 0;

        for (OrbSymbolState state : stateStore.all()) {
            if (state.isBuyAlerted() && state.isSellAlerted()) continue;

            String symbol        = state.getSymbol();
            String instrumentKey = state.getInstrumentKey();

            try {
                OrbCandle candle = fetchLatestMinuteCandle(symbol, instrumentKey);
                if (candle == null) continue;

                double close = candle.getClose();

                // BUY side
                if (!state.isBuyAlerted()) {
                    if (close > state.getRollingHigh()) {
                        sendBuyAlert(state, candle);
                        state.setBuyAlerted(true);
                        buyFired++;
                    } else {
                        double newHigh = Math.max(state.getRollingHigh(), candle.getHigh());
                        if (newHigh > state.getRollingHigh()) {
                            log.debug("[ORB] {} rollingHigh {} → {}", symbol, state.getRollingHigh(), newHigh);
                            state.setRollingHigh(newHigh);
                        }
                    }
                }

                // SELL side
                if (!state.isSellAlerted()) {
                    if (close < state.getRollingLow()) {
                        sendSellAlert(state, candle);
                        state.setSellAlerted(true);
                        sellFired++;
                    } else {
                        double newLow = Math.min(state.getRollingLow(), candle.getLow());
                        if (newLow < state.getRollingLow()) {
                            log.debug("[ORB] {} rollingLow {} → {}", symbol, state.getRollingLow(), newLow);
                            state.setRollingLow(newLow);
                        }
                    }
                }

            } catch (Exception e) {
                log.error("[ORB] scanBreakouts error for {} ({}): {}", symbol, instrumentKey, e.getMessage());
            }
        }
        log.info("[ORB] Scan done → BUY:{} SELL:{}", buyFired, sellFired);
    }

    public void triggerManualCapture() { captureOpeningCandle(); }
    public void triggerManualScan()    { scanBreakouts(); }

    // ── Upstox 1-min candle fetch ────────────────────────────────────────────
    private OrbCandle fetchLatestMinuteCandle(String symbol, String instrumentKey) {
        try {
            String url = "https://api.upstox.com/v2/historical-candle/intraday/"
                + instrumentKey + "/1minute";

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return null;

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) return null;

            @SuppressWarnings("unchecked")
            List<List<Object>> candles = (List<List<Object>>) data.get("candles");
            if (candles == null || candles.isEmpty()) return null;

            // index 0 = most recent completed candle
            // [0]=timestamp [1]=open [2]=high [3]=low [4]=close [5]=volume [6]=oi
            List<Object> c = candles.get(0);
            String rawTs   = c.get(0).toString().substring(0, 19); // strip +05:30

            return OrbCandle.builder()
                .symbol(symbol)
                .instrumentKey(instrumentKey)
                .candleTime(LocalDateTime.parse(rawTs, DT_FMT))
                .open(toDouble(c.get(1)))
                .high(toDouble(c.get(2)))
                .low(toDouble(c.get(3)))
                .close(toDouble(c.get(4)))
                .volume(toLong(c.get(5)))
                .build();

        } catch (Exception e) {
            log.error("[ORB] fetchLatestMinuteCandle failed for {} ({}): {}", symbol, instrumentKey, e.getMessage());
            return null;
        }
    }

    private void sendBuyAlert(OrbSymbolState state, OrbCandle candle) {
        telegramService.sendMessage(String.format(
            "🟢 *ORB BUY BREAKOUT*%n📌 *%s*%n────────────────%n" +
            "📈 Close:       ₹%.2f%n🎯 Above level: ₹%.2f%n🕐 Time: %s%n📊 Volume: %,d",
            state.getSymbol(), candle.getClose(), state.getRollingHigh(),
            candle.getCandleTime().toLocalTime(), candle.getVolume()));
        log.info("[ORB] 🟢 BUY  {} @ ₹{} > ₹{}", state.getSymbol(), candle.getClose(), state.getRollingHigh());
    }

    private void sendSellAlert(OrbSymbolState state, OrbCandle candle) {
        telegramService.sendMessage(String.format(
            "🔴 *ORB SELL BREAKDOWN*%n📌 *%s*%n────────────────%n" +
            "📉 Close:       ₹%.2f%n🎯 Below level: ₹%.2f%n🕐 Time: %s%n📊 Volume: %,d",
            state.getSymbol(), candle.getClose(), state.getRollingLow(),
            candle.getCandleTime().toLocalTime(), candle.getVolume()));
        log.info("[ORB] 🔴 SELL {} @ ₹{} < ₹{}", state.getSymbol(), candle.getClose(), state.getRollingLow());
    }

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private long toLong(Object val) {
        return val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString());
    }
}