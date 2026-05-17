//package com.trading.algo.service;
//
//
//import java.time.LocalDateTime;
//import java.time.LocalTime;
//import java.time.format.DateTimeFormatter;
//import java.util.List;
//import java.util.Map;
//
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.RestTemplate;
//
//import com.trading.algo.dtos.OrbCandle;
//import com.trading.algo.dtos.OrbConfig;
//import com.trading.algo.dtos.OrbSymbolState;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//
///**
// * Opening Range Breakout (ORB) Scanner — Nifty 500
// *
// * ┌──────────────────────────────────────────────────────────────────┐
// * │  SCHEDULE                                                        │
// * │  9:15  AM  → captureOpeningCandle()  — seeds rollingHigh/Low    │
// * │  9:46  AM  → scanBreakouts()         — first scan               │
// * │  10:01 AM  → scanBreakouts()                                     │
// * │  ...every 15 min...                                              │
// * │  3:01  PM  → scanBreakouts()                                     │
// * │  3:16  PM  → scanBreakouts()   ← last scan (just after 3:15)    │
// * └──────────────────────────────────────────────────────────────────┘
// *
// * BUY logic:
// *   Track rollingHigh (starts at 9:15 candle high).
// *   Each scan: if candle.close > rollingHigh → 🟢 BUY alert (once).
// *              else update rollingHigh = max(rollingHigh, candle.high).
// *
// * SELL logic (mirror):
// *   Track rollingLow (starts at 9:15 candle low).
// *   Each scan: if candle.close < rollingLow → 🔴 SELL alert (once).
// *              else update rollingLow = min(rollingLow, candle.low).
// */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class OrbScannerService {
//
//    private final OrbConfig orbConfig;
//    private final OrbStateStore stateStore;
//    private final TelegramService telegramService;
//    private final UpstoxInstrumentMasterService instrumentMasterService;
//    private final RestTemplate restTemplate;
//
//    private static final DateTimeFormatter DT_FMT  = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
//    private static final LocalTime SCAN_START = LocalTime.of(9, 46);
//    private static final LocalTime SCAN_END   = LocalTime.of(15, 16);   // 15:16 to cover 15:15 candle
//
//    // ── 9:15 AM: Capture opening candles ────────────────────────────────────
//    @Scheduled(cron = "0 15 9 * * MON-FRI")
//    public void captureOpeningCandle() {
//        log.info("[ORB] Capturing 9:15 opening candles...");
//        stateStore.clear();
//        List<String> instruments = orbConfig.getNifty500InstrumentKeys();
//
//        for (String instrumentKey : instruments) {
//            try {
//                OrbCandle candle = fetchLatestMinuteCandle(instrumentKey);
//                if (candle == null) continue;
//
//                OrbSymbolState state = OrbSymbolState.builder()
//                    .symbol(candle.getSymbol())
//                    .instrumentKey(instrumentKey)
//                    .rollingHigh(candle.getHigh())
//                    .rollingLow(candle.getLow())
//                    .buyAlerted(false)
//                    .sellAlerted(false)
//                    .build();
//
//                stateStore.put(candle.getSymbol(), state);
//                log.debug("[ORB] Seeded {} H:{} L:{}", candle.getSymbol(), candle.getHigh(), candle.getLow());
//
//            } catch (Exception e) {
//                log.error("[ORB] captureOpeningCandle error for {}: {}", instrumentKey, e.getMessage());
//            }
//        }
//        log.info("[ORB] Seeded {} symbols. Scan starts at 9:46.", stateStore.size());
//    }
//
//    // ── Every 15 min from 9:46 to 15:16 ─────────────────────────────────────
//    // Cron fires at :01, :16, :31, :46 of each hour.
//    // We gate on SCAN_START / SCAN_END so the same cron covers all required slots.
//    @Scheduled(cron = "0 1,16,31,46 9-15 * * MON-FRI")
//    public void scanBreakouts() {
//        LocalTime now = LocalTime.now();
//        if (now.isBefore(SCAN_START) || now.isAfter(SCAN_END)) return;
//
//        log.info("[ORB] Breakout scan running at {}, watching {} symbols", now, stateStore.size());
//        int buyFired = 0, sellFired = 0;
//
//        for (OrbSymbolState state : stateStore.all()) {
//            if (state.isBuyAlerted() && state.isSellAlerted()) continue;
//
//            try {
//                OrbCandle candle = fetchLatestMinuteCandle(state.getInstrumentKey());
//                if (candle == null) continue;
//
//                double close = candle.getClose();
//
//                // BUY side
//                if (!state.isBuyAlerted()) {
//                    if (close > state.getRollingHigh()) {
//                        sendBuyAlert(state, candle);
//                        state.setBuyAlerted(true);
//                        buyFired++;
//                    } else {
//                        double newHigh = Math.max(state.getRollingHigh(), candle.getHigh());
//                        if (newHigh > state.getRollingHigh()) {
//                            log.debug("[ORB] {} rollingHigh: {} → {}", state.getSymbol(), state.getRollingHigh(), newHigh);
//                            state.setRollingHigh(newHigh);
//                        }
//                    }
//                }
//
//                // SELL side
//                if (!state.isSellAlerted()) {
//                    if (close < state.getRollingLow()) {
//                        sendSellAlert(state, candle);
//                        state.setSellAlerted(true);
//                        sellFired++;
//                    } else {
//                        double newLow = Math.min(state.getRollingLow(), candle.getLow());
//                        if (newLow < state.getRollingLow()) {
//                            log.debug("[ORB] {} rollingLow: {} → {}", state.getSymbol(), state.getRollingLow(), newLow);
//                            state.setRollingLow(newLow);
//                        }
//                    }
//                }
//
//            } catch (Exception e) {
//                log.error("[ORB] scanBreakouts error for {}: {}", state.getSymbol(), e.getMessage());
//            }
//        }
//        log.info("[ORB] Scan done at {} → BUY:{} SELL:{}", now, buyFired, sellFired);
//    }
//
//    // ── Manual triggers (called from OrbController) ──────────────────────────
//    public void triggerManualCapture() { captureOpeningCandle(); }
//    public void triggerManualScan()    { scanBreakouts(); }
//
//    // ── Upstox intraday 1-minute candle fetch ────────────────────────────────
//    private OrbCandle fetchLatestMinuteCandle(String instrumentKey) {
//        try {
//            String url = "https://api.upstox.com/v2/historical-candle/intraday/"
//                + instrumentKey + "/1minute";
//
//            @SuppressWarnings("unchecked")
//            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
//            if (response == null || !"success".equals(response.get("status"))) return null;
//
//            @SuppressWarnings("unchecked")
//            Map<String, Object> data = (Map<String, Object>) response.get("data");
//            if (data == null) return null;
//
//            @SuppressWarnings("unchecked")
//            List<List<Object>> candles = (List<List<Object>>) data.get("candles");
//            if (candles == null || candles.isEmpty()) return null;
//
//            // Index 0 = most recent completed 1-minute candle
//            List<Object> c = candles.get(0);
//            // [0]=timestamp, [1]=open, [2]=high, [3]=low, [4]=close, [5]=volume, [6]=oi
//
//            String symbol = instrumentMasterService.resolveSymbolFromInstrumentKey(instrumentKey);
//            String rawTs  = c.get(0).toString().substring(0, 19); // strip timezone
//
//            return OrbCandle.builder()
//                .symbol(symbol)
//                .instrumentKey(instrumentKey)
//                .candleTime(LocalDateTime.parse(rawTs, DT_FMT))
//                .open(toDouble(c.get(1)))
//                .high(toDouble(c.get(2)))
//                .low(toDouble(c.get(3)))
//                .close(toDouble(c.get(4)))
//                .volume(toLong(c.get(5)))
//                .build();
//
//        } catch (Exception e) {
//            log.error("[ORB] fetchLatestMinuteCandle failed for {}: {}", instrumentKey, e.getMessage());
//            return null;
//        }
//    }
//
//    // ── Telegram message builders ────────────────────────────────────────────
//    private void sendBuyAlert(OrbSymbolState state, OrbCandle candle) {
//        String msg = String.format(
//            "🟢 *ORB BUY BREAKOUT*%n" +
//            "📌 *%s*%n" +
//            "────────────────%n" +
//            "📈 Close:       ₹%.2f%n" +
//            "🎯 Above level: ₹%.2f%n" +
//            "🕐 Time:        %s%n" +
//            "📊 Volume:      %,d",
//            state.getSymbol(),
//            candle.getClose(),
//            state.getRollingHigh(),
//            candle.getCandleTime().toLocalTime(),
//            candle.getVolume()
//        );
//        telegramService.sendMessage(msg);
//        log.info("[ORB] 🟢 BUY  {} @ ₹{} > ₹{}", state.getSymbol(), candle.getClose(), state.getRollingHigh());
//    }
//
//    private void sendSellAlert(OrbSymbolState state, OrbCandle candle) {
//        String msg = String.format(
//            "🔴 *ORB SELL BREAKDOWN*%n" +
//            "📌 *%s*%n" +
//            "────────────────%n" +
//            "📉 Close:       ₹%.2f%n" +
//            "🎯 Below level: ₹%.2f%n" +
//            "🕐 Time:        %s%n" +
//            "📊 Volume:      %,d",
//            state.getSymbol(),
//            candle.getClose(),
//            state.getRollingLow(),
//            candle.getCandleTime().toLocalTime(),
//            candle.getVolume()
//        );
//        telegramService.sendMessage(msg);
//        log.info("[ORB] 🔴 SELL {} @ ₹{} < ₹{}", state.getSymbol(), candle.getClose(), state.getRollingLow());
//    }
//
//    // ── Type helpers ─────────────────────────────────────────────────────────
//    private double toDouble(Object val) {
//        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
//    }
//
//    private long toLong(Object val) {
//        return val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString());
//    }
//}

