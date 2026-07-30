package com.trading.algo.orb;

import com.trading.algo.dtos.OrbCandle;
import com.trading.algo.dtos.OrbSymbolState;
import com.trading.algo.entity.WatchlistAlert;
import com.trading.algo.service.UniverseService;
import com.trading.algo.service.WatchlistAlertService;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ORB Strategy service for watchlist stocks.
 * 
 * Runs every 15 minutes after a watchlist alert is received.
 * Applies ORB strategy only to stocks from the latest watchlist alert.
 * 
 * Key differences from standard ORB:
 * - Universe is limited to watchlist stocks (not Nifty 500)
 * - Runs every 15 minutes (not just at scheduled times)
 * - Processes alerts as they come in
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistOrbService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final LocalTime CAPTURE_AT = LocalTime.of(9, 31);
    private static final LocalTime SCAN_START = LocalTime.of(9, 46);
    private static final LocalTime SCAN_END = LocalTime.of(15, 16);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 15);

    // Filters
    private static final double MIN_RANGE_PCT = 0.5;
    private static final double MIN_VOLUME_MULTIPLIER = 1.5;

    private final WatchlistAlertService watchlistAlertService;
    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final TelegramService telegramService;
    private final OrbStateStore stateStore;

    // In-memory state for watchlist ORB (separate from main ORB state store)
    private final Map<String, OrbSymbolState> watchlistStateMap = new ConcurrentHashMap<>();

    /**
     * Main entry point - called by scheduler every 15 minutes
     */
    public void processWatchlistOrb() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(SCAN_START) || now.isAfter(SCAN_END)) {
            log.debug("Outside ORB scan window: {}", now);
            return;
        }

        // Get the latest unprocessed watchlist alert
        WatchlistAlert alert = watchlistAlertService.getLatestUnprocessedOrbAlert();
        if (alert == null) {
            log.debug("No unprocessed watchlist alert for ORB");
            return;
        }

        log.info("Processing ORB for watchlist alert {} with {} symbols", 
                alert.getId(), alert.getTotalSymbols());

        // Filter to F&O symbols only
        List<String> fnoSymbols = alert.getSymbols().stream()
                .filter(UniverseService.NIFTY_FNO_SYMBOLS::contains)
                .distinct()
                .collect(Collectors.toList());

        if (fnoSymbols.isEmpty()) {
            log.warn("No F&O symbols in watchlist alert");
            watchlistAlertService.markOrbProcessed(alert.getId());
            return;
        }

        log.info("Filtered to {} F&O symbols from watchlist", fnoSymbols.size());

        // Initialize state if this is the first scan of the day
        if (watchlistStateMap.isEmpty()) {
            initializeWatchlistState(fnoSymbols, LocalDate.now());
        }

        // Scan for breakouts
        scanBreakouts(fnoSymbols, LocalDate.now());

        // Mark alert as processed
        watchlistAlertService.markOrbProcessed(alert.getId());
        log.info("Watchlist ORB processing complete for alert {}", alert.getId());
    }

    /**
     * Initialize ORB state for watchlist stocks
     * Captures the 9:15-9:30 opening candle for each symbol
     */
    private void initializeWatchlistState(List<String> symbols, LocalDate today) {
        log.info("Initializing watchlist ORB state for {} symbols", symbols.size());
        
        Map<String, String> symbolKeyMap = instrumentMaster.resolveToInstrumentKeyMap(symbols);
        
        for (Map.Entry<String, String> entry : symbolKeyMap.entrySet()) {
            String symbol = entry.getKey();
            String instrumentKey = entry.getValue();
            
            try {
                OrbCandle candle = fetchLatestCandle(symbol, instrumentKey, today);
                if (candle == null) continue;

                // Filter by minimum opening range
                double rangeWidth = ((candle.getHigh() - candle.getLow()) / candle.getLow()) * 100.0;
                if (rangeWidth < MIN_RANGE_PCT) {
                    log.debug("Watchlist ORB: {} skipped - range too narrow ({:.2f}%)", 
                            symbol, rangeWidth);
                    continue;
                }

                double prevClose = fetchPrevDayClose(symbol, instrumentKey);

                OrbSymbolState state = OrbSymbolState.builder()
                        .symbol(symbol)
                        .instrumentKey(instrumentKey)
                        .rollingHigh(candle.getHigh())
                        .rollingLow(candle.getLow())
                        .buyAlerted(false)
                        .sellAlerted(false)
                        .prevCandleHigh(candle.getHigh())
                        .prevCandleLow(candle.getLow())
                        .openingCandleVolume(candle.getVolume())
                        .openPrice(candle.getOpen())
                        .prevDayClose(prevClose)
                        .build();

                watchlistStateMap.put(symbol, state);
                log.debug("Watchlist ORB: Seeded {} H:{} L:{} range:{:.2f}%", 
                        symbol, candle.getHigh(), candle.getLow(), rangeWidth);

            } catch (Exception e) {
                log.error("Watchlist ORB initialization error for {}: {}", symbol, e.getMessage());
            }
        }
        
        log.info("Watchlist ORB state initialized: {} symbols", watchlistStateMap.size());
    }

    /**
     * Scan for breakouts on watchlist stocks
     */
    private void scanBreakouts(List<String> symbols, LocalDate today) {
        log.info("Scanning watchlist ORB breakouts for {} symbols", symbols.size());
        int buyFired = 0, sellFired = 0;

        for (String symbol : symbols) {
            OrbSymbolState state = watchlistStateMap.get(symbol);
            if (state == null || (state.isBuyAlerted() && state.isSellAlerted())) {
                continue;
            }

            try {
                OrbCandle candle = fetchLatestCandle(symbol, state.getInstrumentKey(), today);
                if (candle == null) continue;

                double close = candle.getClose();
                double candleHigh = candle.getHigh();
                double candleLow = candle.getLow();

                // BUY side
                if (!state.isBuyAlerted()) {
                    if (close > state.getRollingHigh()) {
                        if (candle.getVolume() < (long)(state.getOpeningCandleVolume() * MIN_VOLUME_MULTIPLIER)) {
                            log.debug("Watchlist ORB: {} BUY skipped - low volume", symbol);
                        } else {
                            state.setRollingLow(state.getPrevCandleLow());
                            sendBuyAlert(state, candle);
                            state.setBuyAlerted(true);
                            buyFired++;
                        }
                    } else if (candleHigh > state.getRollingHigh()) {
                        log.debug("Watchlist ORB: {} rollingHigh raised to {}", symbol, candleHigh);
                        state.setRollingHigh(candleHigh);
                    }
                }

                // SELL side
                if (!state.isSellAlerted()) {
                    if (close < state.getRollingLow()) {
                        if (candle.getVolume() < (long)(state.getOpeningCandleVolume() * MIN_VOLUME_MULTIPLIER)) {
                            log.debug("Watchlist ORB: {} SELL skipped - low volume", symbol);
                        } else {
                            state.setRollingHigh(state.getPrevCandleHigh());
                            sendSellAlert(state, candle);
                            state.setSellAlerted(true);
                            sellFired++;
                        }
                    } else if (candleLow < state.getRollingLow()) {
                        log.debug("Watchlist ORB: {} rollingLow dropped to {}", symbol, candleLow);
                        state.setRollingLow(candleLow);
                    }
                }

                state.setPrevCandleHigh(candleHigh);
                state.setPrevCandleLow(candleLow);

            } catch (Exception e) {
                log.error("Watchlist ORB scan error for {}: {}", symbol, e.getMessage());
            }
        }

        log.info("Watchlist ORB scan complete - BUY:{} SELL:{}", buyFired, sellFired);
    }

    /**
     * Fetch latest 15-minute candle for a symbol
     */
    private OrbCandle fetchLatestCandle(String symbol, String instrumentKey, LocalDate today) {
        try {
            List<com.trading.algo.dtos.Candle> candles = candleService.fetchDayCandles(instrumentKey, today);
            if (candles == null || candles.isEmpty()) return null;

            // Get the most recent candle
            com.trading.algo.dtos.Candle latest = candles.get(0);

            return OrbCandle.builder()
                    .symbol(symbol)
                    .instrumentKey(instrumentKey)
                    .candleTime(latest.getTimestamp())
                    .open(latest.getOpen())
                    .high(latest.getHigh())
                    .low(latest.getLow())
                    .close(latest.getClose())
                    .volume(latest.getVolume())
                    .build();

        } catch (Exception e) {
            log.error("Watchlist ORB fetch candle failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch previous day's close price
     */
    private double fetchPrevDayClose(String symbol, String instrumentKey) {
        try {
            LocalDate today = LocalDate.now();
            LocalDate fromDate = today.minusDays(5);
            
            List<com.trading.algo.dtos.Candle> candles = candleService.fetchDailyCandles(
                    instrumentKey, fromDate, today);
            
            if (candles == null || candles.isEmpty()) return 0;
            
            // Get the most recent completed trading day (last in the list)
            return candles.get(candles.size() - 1).getClose();

        } catch (Exception e) {
            log.warn("Watchlist ORB fetch prev close failed for {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    /**
     * Send BUY alert to Telegram
     */
    private void sendBuyAlert(OrbSymbolState state, OrbCandle candle) {
        double close = candle.getClose();
        double slLevel = state.getPrevCandleLow();
        double riskPct = ((close - slLevel) / close) * 100.0;
        double movePct = ((close - state.getOpenPrice()) / state.getOpenPrice()) * 100.0;
        double gapPct = state.getPrevDayClose() > 0
                ? ((state.getOpenPrice() - state.getPrevDayClose()) / state.getPrevDayClose()) * 100.0
                : 0;
        String gapStr = state.getPrevDayClose() > 0
                ? String.format("%s%.2f%%", gapPct >= 0 ? "+" : "", gapPct)
                : "N/A";

        String message = String.format(
                "🟢 *WATCHLIST ORB BUY*%n" +
                "📌 *%s*%n" +
                "────────────────%n" +
                "📈 Close:        ₹%.2f%n" +
                "🎯 Above level:  ₹%.2f%n" +
                "🛑 SL:           ₹%.2f  (%.2f%% risk)%n" +
                "📊 Move from open: %s%.2f%%%n" +
                "🌅 Gap from prev:  %s%n" +
                "📅 Prev close:   ₹%.2f%n" +
                "🕐 Candle:       %s%n" +
                "📊 Volume:       %,d%n",
                state.getSymbol(),
                close,
                state.getRollingHigh(),
                slLevel, riskPct,
                movePct >= 0 ? "+" : "", movePct,
                gapStr,
                state.getPrevDayClose(),
                candlePeriod(candle.getCandleTime().toLocalTime()),
                candle.getVolume()
        );

        telegramService.sendMessage(message);
        log.info("Watchlist ORB BUY alert sent for {}", state.getSymbol());
    }

    /**
     * Send SELL alert to Telegram
     */
    private void sendSellAlert(OrbSymbolState state, OrbCandle candle) {
        double close = candle.getClose();
        double slLevel = state.getPrevCandleHigh();
        double riskPct = ((slLevel - close) / close) * 100.0;
        double movePct = ((close - state.getOpenPrice()) / state.getOpenPrice()) * 100.0;
        double gapPct = state.getPrevDayClose() > 0
                ? ((state.getOpenPrice() - state.getPrevDayClose()) / state.getPrevDayClose()) * 100.0
                : 0;
        String gapStr = state.getPrevDayClose() > 0
                ? String.format("%s%.2f%%", gapPct >= 0 ? "+" : "", gapPct)
                : "N/A";

        String message = String.format(
                "🔴 *WATCHLIST ORB SELL*%n" +
                "📌 *%s*%n" +
                "────────────────%n" +
                "📉 Close:        ₹%.2f%n" +
                "🎯 Below level:  ₹%.2f%n" +
                "🛑 SL:           ₹%.2f  (%.2f%% risk)%n" +
                "📊 Move from open: %s%.2f%%%n" +
                "🌅 Gap from prev:  %s%n" +
                "📅 Prev close:   ₹%.2f%n" +
                "🕐 Candle:       %s%n" +
                "📊 Volume:       %,d%n",
                state.getSymbol(),
                close,
                state.getRollingLow(),
                slLevel, riskPct,
                movePct >= 0 ? "+" : "", movePct,
                gapStr,
                state.getPrevDayClose(),
                candlePeriod(candle.getCandleTime().toLocalTime()),
                candle.getVolume()
        );

        telegramService.sendMessage(message);
        log.info("Watchlist ORB SELL alert sent for {}", state.getSymbol());
    }

    private String candlePeriod(LocalTime candleOpen) {
        LocalTime candleClose = candleOpen.plusMinutes(15);
        return String.format("%s–%s",
                candleOpen.format(DateTimeFormatter.ofPattern("HH:mm")),
                candleClose.format(DateTimeFormatter.ofPattern("HH:mm")));
    }

    /**
     * Clear state at end of day
     */
    public void clearState() {
        watchlistStateMap.clear();
        log.info("Watchlist ORB state cleared");
    }

    /**
     * Manual trigger for testing
     */
    public void manualTrigger() {
        processWatchlistOrb();
    }
}
