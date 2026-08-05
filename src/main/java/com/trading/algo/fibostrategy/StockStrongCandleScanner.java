package com.trading.algo.fibostrategy;

import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scans stocks for strong daily candles and categorizes them as:
 * 1. Fibo Strategy Movers - Strong candles that match the fibo opening candle strategy
 * 2. Other Movers - Strong candles that moved but don't match fibo strategy
 *
 * Helps identify actual patterns that moved the stock beyond the fibo strategy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockStrongCandleScanner {

    private static final LocalTime C1_TIME = LocalTime.of(9, 15);
    private static final LocalTime C2_TIME = LocalTime.of(9, 30);

    private final UpstoxHistoricalCandleService candleService;
    private final OpeningCandleStrategyService strategyService;
    private final BacktestConfig config;
    private final TelegramService telegramService;
    private final UpstoxInstrumentMasterService instrumentMasterService;
    private final UniverseService universeService;

    /**
     * Scan stocks for strong candles - runs daily after market close at 3:35 PM
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanDailyStrongCandles() {
        LocalDate today = LocalDate.now();
        log.info("Starting strong candle scan for {}", today);
        
        // TODO: Get list of stocks to scan - for now use a sample list
        // This should be configured or fetched from F&O list/watchlist
        List<String> symbols = getStocksToScan();
        
        List<StrongCandleAlert> fiboMovers = new ArrayList<>();
        List<StrongCandleAlert> otherMovers = new ArrayList<>();
        
        for (String symbol : symbols) {
            try {
                Thread.sleep(config.getApiDelayMs());
                
                // Fetch instrument key for the symbol
                String instrumentKey = getInstrumentKey(symbol);
                if (instrumentKey == null) {
                    log.debug("No instrument key found for {}", symbol);
                    continue;
                }
                
                // Fetch daily candles for today
                List<Candle> candles = candleService.fetchDayCandles(instrumentKey, today);
                if (candles.isEmpty()) {
                    log.debug("No candles found for {} on {}", symbol, today);
                    continue;
                }
                
                // Find C1 (9:15 candle)
                Candle c1 = findCandle(candles, C1_TIME);
                if (c1 == null) {
                    log.debug("C1 not found for {} on {}", symbol, today);
                    continue;
                }
                
                // Check if C1 is a strong candle
                if (!isStrongCandle(c1, 0.0, 0)) {
                    log.debug("{} - C1 is not a strong candle", symbol);
                    continue;
                }
                
                // Find C2 (9:30 candle)
                Candle c2 = findCandle(candles, C2_TIME);
                if (c2 == null) {
                    log.debug("C2 not found for {} on {}", symbol, today);
                    continue;
                }
                
                // Check if it matches fibo strategy
                BacktestTrade.Direction direction = c1.isBullish() ? BacktestTrade.Direction.BUY : BacktestTrade.Direction.SELL;
                boolean isFiboSetup = isValidC2(c1, c2, direction);
                
                StrongCandleAlert alert = StrongCandleAlert.builder()
                        .symbol(symbol)
                        .date(today)
                        .direction(direction)
                        .c1Open(c1.getOpen())
                        .c1High(c1.getHigh())
                        .c1Low(c1.getLow())
                        .c1Close(c1.getClose())
                        .c1Body(c1.body())
                        .c1WickRatio(c1.wickRatio())
                        .c1Range(c1.range())
                        .c1Volume(c1.getVolume())
                        .c2Open(c2.getOpen())
                        .c2High(c2.getHigh())
                        .c2Low(c2.getLow())
                        .c2Close(c2.getClose())
                        .dayChange(c1.getClose() - c1.getOpen())
                        .dayChangePct((c1.getClose() - c1.getOpen()) / c1.getOpen() * 100)
                        .build();
                
                if (isFiboSetup) {
                    fiboMovers.add(alert);
                    log.info("{} - FIBO STRATEGY MOVER - {} {}%", symbol, direction, String.format("%.2f", alert.getDayChangePct()));
                } else {
                    otherMovers.add(alert);
                    log.info("{} - OTHER MOVER - {} {}%", symbol, direction, String.format("%.2f", alert.getDayChangePct()));
                }
                
            } catch (Exception e) {
                log.error("Error scanning {}: {}", symbol, e.getMessage());
            }
        }
        
        // Send alerts
        if (!fiboMovers.isEmpty()) {
            sendFiboMoversAlert(fiboMovers, today);
        }
        if (!otherMovers.isEmpty()) {
            sendOtherMoversAlert(otherMovers, today);
        }
        
        log.info("Strong candle scan complete - Fibo movers: {}, Other movers: {}", fiboMovers.size(), otherMovers.size());
    }
    
    private List<String> getStocksToScan() {
        // Use the F&O universe from UniverseService
        return UniverseService.NIFTY_FNO_SYMBOLS;
    }
    
    private String getInstrumentKey(String symbol) {
        return instrumentMasterService.getInstrumentKey(symbol).orElse(null);
    }
    
    private Candle findCandle(List<Candle> candles, LocalTime time) {
        return candles.stream()
                .filter(c -> c.getTimestamp().toLocalTime().equals(time))
                .findFirst()
                .orElse(null);
    }
    
    private boolean isStrongCandle(Candle c, double dailyAtr, long avgC1Volume) {
        if (c.body() < config.getMinCandleBodyPoints()) return false;
        if (c.wickRatio() < config.getMinWickRatio()) return false;
        if (c.getOpen() > 0 && c.body() / c.getOpen() * 100.0 < config.getMinC1BodyPct()) return false;
        if (dailyAtr > 0 && c.range() < config.getMinC1AtrRatio() * dailyAtr) return false;
        if (avgC1Volume > 0 && c.getVolume() < config.getMinC1VolumeMultiplier() * avgC1Volume) return false;
        return true;
    }
    
    private boolean isValidC2(Candle c1, Candle c2, BacktestTrade.Direction direction) {
        // C2 volume must be lower than C1 volume
        if (c2.getVolume() >= c1.getVolume()) {
            return false;
        }
        
        double fifty = c1.fiftyPercent();
        double c1Range = c1.range();
        double ext414 = c1Range * 0.414;
        
        if (direction == BacktestTrade.Direction.BUY) {
            boolean aboveFifty = c2.getLow() > fifty;
            double extLevel = c1.getHigh() + ext414;
            boolean withinExt = c2.getHigh() <= extLevel;
            
            double fib382 = c1.getHigh() - (c1Range * 0.382);
            boolean fib382ok = c2.getLow() <= fib382 ? c2.getClose() > fib382 : true;
            
            return aboveFifty && withinExt && fib382ok;
        } else {
            boolean belowFifty = c2.getHigh() < fifty;
            double extLevel = c1.getLow() - ext414;
            boolean withinExt = c2.getLow() >= extLevel;
            
            double fib382 = c1.getLow() + (c1Range * 0.382);
            boolean fib382ok = c2.getHigh() >= fib382 ? c2.getClose() < fib382 : true;
            
            return belowFifty && withinExt && fib382ok;
        }
    }
    
    private void sendFiboMoversAlert(List<StrongCandleAlert> alerts, LocalDate date) {
        StringBuilder message = new StringBuilder();
        message.append("🎯 *Fibo Strategy Movers*\n");
        message.append("━━━━━━━━━━━━━━━━━━━━\n");
        message.append("Strong candles matching fibo strategy on ").append(date).append(":\n\n");
        
        for (StrongCandleAlert alert : alerts) {
            String emoji = alert.getDirection() == BacktestTrade.Direction.BUY ? "🟢" : "🔴";
            message.append(String.format(
                    "%s *%s*\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    "C1: O=%.2f H=%.2f L=%.2f C=%.2f\n" +
                    "Body: %.2f | Wick Ratio: %.3f | Range: %.2f\n" +
                    "Day Change: %.2f (%.2f%%)\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n\n",
                    emoji, alert.getSymbol(),
                    alert.getC1Open(), alert.getC1High(), alert.getC1Low(), alert.getC1Close(),
                    alert.getC1Body(), alert.getC1WickRatio(), alert.getC1Range(),
                    alert.getDayChange(), alert.getDayChangePct()
            ));
        }
        
        message.append(String.format("Total fibo movers: %d", alerts.size()));
        telegramService.sendMessageToIntraday(message.toString());
    }
    
    private void sendOtherMoversAlert(List<StrongCandleAlert> alerts, LocalDate date) {
        StringBuilder message = new StringBuilder();
        message.append("📊 *Other Strong Candle Movers*\n");
        message.append("━━━━━━━━━━━━━━━━━━━━\n");
        message.append("Strong candles NOT matching fibo strategy on ").append(date).append(":\n\n");
        
        for (StrongCandleAlert alert : alerts) {
            String emoji = alert.getDirection() == BacktestTrade.Direction.BUY ? "🟢" : "🔴";
            message.append(String.format(
                    "%s *%s*\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    "C1: O=%.2f H=%.2f L=%.2f C=%.2f\n" +
                    "Body: %.2f | Wick Ratio: %.3f | Range: %.2f\n" +
                    "Day Change: %.2f (%.2f%%)\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n\n",
                    emoji, alert.getSymbol(),
                    alert.getC1Open(), alert.getC1High(), alert.getC1Low(), alert.getC1Close(),
                    alert.getC1Body(), alert.getC1WickRatio(), alert.getC1Range(),
                    alert.getDayChange(), alert.getDayChangePct()
            ));
        }
        
        message.append(String.format("Total other movers: %d", alerts.size()));
        telegramService.sendMessageToIntraday(message.toString());
    }
    
    @lombok.Data
    @lombok.Builder
    private static class StrongCandleAlert {
        private String symbol;
        private LocalDate date;
        private BacktestTrade.Direction direction;
        private double c1Open;
        private double c1High;
        private double c1Low;
        private double c1Close;
        private double c1Body;
        private double c1WickRatio;
        private double c1Range;
        private long c1Volume;
        private double c2Open;
        private double c2High;
        private double c2Low;
        private double c2Close;
        private double dayChange;
        private double dayChangePct;
    }
}
