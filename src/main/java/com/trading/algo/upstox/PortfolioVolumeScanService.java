package com.trading.algo.upstox;

import com.trading.algo.broker.UnifiedPortfolioService;
import com.trading.algo.discord.DiscordService;
import com.trading.algo.dtos.Candle;
import com.trading.algo.telegram.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans portfolio holdings for volume spike and price movement conditions across multiple timeframes.
 *
 * Volume Conditions:
 * - Daily: current day volume exceeds 1.5x avg daily volume (last 20 candles)
 * - Weekly: current week volume exceeds 1.5x avg weekly volume (last 20 candles)
 * - Monthly: current month volume exceeds 1.5x avg monthly volume (last 20 candles)
 * 
 * Price Movement Conditions:
 * - Daily: current close changes by >= 2.5% from previous day close
 * - Weekly: current close changes by >= 2.5% from previous week close
 * - Monthly: current close changes by >= 2.5% from previous month close
 * 
 * For new stocks with limited history, uses available data.
 * Runs daily at 3:35 PM after market close.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioVolumeScanService {

    @Value("${portfolio.volume.scan.multiplier:1.5}")
    private double volumeMultiplier;

    @Value("${portfolio.volume.scan.daily.lookback:20}")
    private int dailyLookback;

    @Value("${portfolio.volume.scan.weekly.lookback:20}")
    private int weeklyLookback;

    @Value("${portfolio.volume.scan.monthly.lookback:20}")
    private int monthlyLookback;

    @Value("${portfolio.volume.scan.min-history-days:5}")
    private int minHistoryDays;

    @Value("${portfolio.price.movement.threshold:2.5}")
    private double priceMovementThreshold;

    private final UnifiedPortfolioService unifiedPortfolioService;
    private final UpstoxHistoricalCandleService upstoxHistoricalCandleService;
    private final TelegramService telegramService;
    private final DiscordService discordService;

    /**
     * Main scan method - fetches portfolio from all brokers and checks volume and price movement conditions.
     */
    public void scanPortfolioForVolumeSpike() {
        log.info("Starting portfolio volume and price movement scan across all brokers");

        List<PortfolioHolding> holdings = unifiedPortfolioService.fetchAllHoldings();
        if (holdings.isEmpty()) {
            log.warn("No portfolio holdings found from any broker");
            telegramService.sendMessage("📊 Portfolio Scan\n\nNo holdings found in portfolio.");
            return;
        }

        List<VolumeAlert> volumeAlerts = new ArrayList<>();
        List<PriceMovementAlert> priceAlerts = new ArrayList<>();

        for (PortfolioHolding holding : holdings) {
            try {
                // Check daily timeframe
                VolumeAlert dailyVolumeAlert = checkVolumeCondition(holding, "DAILY", dailyLookback);
                if (dailyVolumeAlert != null) {
                    volumeAlerts.add(dailyVolumeAlert);
                }
                PriceMovementAlert dailyPriceAlert = checkPriceMovementCondition(holding, "DAILY");
                if (dailyPriceAlert != null) {
                    priceAlerts.add(dailyPriceAlert);
                }

                // Check weekly timeframe
                VolumeAlert weeklyVolumeAlert = checkVolumeCondition(holding, "WEEKLY", weeklyLookback);
                if (weeklyVolumeAlert != null) {
                    volumeAlerts.add(weeklyVolumeAlert);
                }
                PriceMovementAlert weeklyPriceAlert = checkPriceMovementCondition(holding, "WEEKLY");
                if (weeklyPriceAlert != null) {
                    priceAlerts.add(weeklyPriceAlert);
                }

                // Check monthly timeframe
                VolumeAlert monthlyVolumeAlert = checkVolumeCondition(holding, "MONTHLY", monthlyLookback);
                if (monthlyVolumeAlert != null) {
                    volumeAlerts.add(monthlyVolumeAlert);
                }
                PriceMovementAlert monthlyPriceAlert = checkPriceMovementCondition(holding, "MONTHLY");
                if (monthlyPriceAlert != null) {
                    priceAlerts.add(monthlyPriceAlert);
                }
            } catch (Exception e) {
                log.error("Error checking conditions for {}: {}", 
                    holding.getTradingSymbol(), e.getMessage());
            }
        }

        // Send volume alerts
        if (!volumeAlerts.isEmpty()) {
            sendVolumeAlerts(volumeAlerts);
        }

        // Send price movement alerts
        if (!priceAlerts.isEmpty()) {
            sendPriceMovementAlerts(priceAlerts);
        }

        if (volumeAlerts.isEmpty() && priceAlerts.isEmpty()) {
            log.info("No alerts generated for {} holdings", holdings.size());
            telegramService.sendMessage(String.format(
                "📊 Portfolio Scan\n\nScanned %d holdings across %s. No volume spike or price movement detected.",
                holdings.size(),
                String.join(", ", unifiedPortfolioService.getEnabledBrokers())
            ));
        }
    }

    /**
     * Checks if a holding meets the volume spike condition for a specific timeframe.
     * Condition: Current period volume exceeds 1.5x average volume of lookback period
     * 
     * @param holding the portfolio holding
     * @param timeframe the timeframe to check (DAILY, WEEKLY, MONTHLY)
     * @param lookbackPeriod number of candles to use for average calculation
     * @return VolumeAlert if condition met, null otherwise
     */
    private VolumeAlert checkVolumeCondition(PortfolioHolding holding, String timeframe, int lookbackPeriod) {
        LocalDate today = LocalDate.now();
        String instrumentKey = holding.getInstrumentToken();

        // Fetch current period candle based on timeframe
        List<Candle> currentCandles = fetchCandlesForTimeframe(instrumentKey, timeframe, today);
        if (currentCandles.isEmpty()) {
            log.debug("No {} candles found for {} on {}", timeframe, holding.getTradingSymbol(), today);
            return null;
        }

        // Convert to single candle for the period
        Candle currentPeriod = toPeriodCandle(currentCandles);

        // Fetch historical candles for average volume calculation
        LocalDate fromDate = calculateFromDate(today, timeframe, lookbackPeriod);
        List<Candle> historicalCandles = fetchHistoricalCandles(instrumentKey, timeframe, fromDate, today.minusDays(1));

        // Handle new stocks with limited history
        int availableLookback = historicalCandles.size();
        if (availableLookback < minHistoryDays) {
            log.debug("Not enough historical data for {} {} (got {}, minimum {})", 
                holding.getTradingSymbol(), timeframe, availableLookback, minHistoryDays);
            return null;
        }

        // Use available data if less than requested lookback (new stock handling)
        int actualLookback = Math.min(availableLookback, lookbackPeriod);
        double avgVolume = calculateAverageVolume(historicalCandles, actualLookback);

        // Check if current volume exceeds average by multiplier
        double volumeRatio = (double) currentPeriod.getVolume() / avgVolume;
        if (volumeRatio < volumeMultiplier) {
            log.debug("{} {} - Volume ratio {} below threshold {}", 
                holding.getTradingSymbol(), timeframe, volumeRatio, volumeMultiplier);
            return null;
        }

        // Condition met - create alert
        return VolumeAlert.builder()
                .symbol(holding.getTradingSymbol())
                .companyName(holding.getCompanyName())
                .closePrice(currentPeriod.getClose())
                .openPrice(currentPeriod.getOpen())
                .volume(currentPeriod.getVolume())
                .avgVolume(avgVolume)
                .volumeRatio(volumeRatio)
                .dayChange(holding.getDayChange())
                .dayChangePercentage(holding.getDayChangePercentage())
                .quantity(holding.getQuantity())
                .pnl(holding.getPnl())
                .timeframe(timeframe)
                .currentVolume(currentPeriod.getVolume())
                .avgVolumeTimeframe((long) avgVolume)
                .volumeRatioTimeframe(volumeRatio)
                .lookbackPeriod(actualLookback)
                .broker("UPSTOX") // TODO: Update when broker info is added to PortfolioHolding
                .build();
    }

    /**
     * Fetches candles for the current period based on timeframe.
     */
    private List<Candle> fetchCandlesForTimeframe(String instrumentKey, String timeframe, LocalDate date) {
        return switch (timeframe) {
            case "DAILY" -> upstoxHistoricalCandleService.fetchDayCandles(instrumentKey, date);
            case "WEEKLY" -> upstoxHistoricalCandleService.fetchWeeklyCandles(instrumentKey, date, date);
            case "MONTHLY" -> upstoxHistoricalCandleService.fetchMonthlyCandles(instrumentKey, date, date);
            default -> {
                log.warn("Unknown timeframe: {}", timeframe);
                yield List.of();
            }
        };
    }

    /**
     * Fetches historical candles based on timeframe.
     */
    private List<Candle> fetchHistoricalCandles(String instrumentKey, String timeframe, LocalDate fromDate, LocalDate toDate) {
        return switch (timeframe) {
            case "DAILY" -> upstoxHistoricalCandleService.fetchDailyCandles(instrumentKey, fromDate, toDate);
            case "WEEKLY" -> upstoxHistoricalCandleService.fetchWeeklyCandles(instrumentKey, fromDate, toDate);
            case "MONTHLY" -> upstoxHistoricalCandleService.fetchMonthlyCandles(instrumentKey, fromDate, toDate);
            default -> {
                log.warn("Unknown timeframe: {}", timeframe);
                yield List.of();
            }
        };
    }

    /**
     * Calculates the from date based on timeframe and lookback period.
     */
    private LocalDate calculateFromDate(LocalDate today, String timeframe, int lookbackPeriod) {
        return switch (timeframe) {
            case "DAILY" -> today.minusDays(lookbackPeriod + 10); // Buffer for weekends
            case "WEEKLY" -> today.minusWeeks(lookbackPeriod + 2); // Buffer for partial weeks
            case "MONTHLY" -> today.minusMonths(lookbackPeriod + 2); // Buffer for partial months
            default -> today.minusDays(lookbackPeriod + 10);
        };
    }

    /**
     * Converts multiple candles to a single period candle.
     */
    private Candle toPeriodCandle(List<Candle> candles) {
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("Cannot convert empty candle list");
        }

        candles.sort(java.util.Comparator.comparing(Candle::getTimestamp));

        Candle first = candles.get(0);
        Candle last = candles.get(candles.size() - 1);
        double high = candles.stream().mapToDouble(Candle::getHigh).max().orElse(first.getHigh());
        double low = candles.stream().mapToDouble(Candle::getLow).min().orElse(first.getLow());
        long volume = candles.stream().mapToLong(Candle::getVolume).sum();

        return Candle.builder()
                .timestamp(first.getTimestamp())
                .open(first.getOpen())
                .high(high)
                .low(low)
                .close(last.getClose())
                .volume(volume)
                .build();
    }

    /**
     * Calculates average volume from the last N candles.
     */
    private double calculateAverageVolume(List<Candle> candles, int lookbackPeriod) {
        int fromIndex = Math.max(0, candles.size() - lookbackPeriod);
        List<Candle> lastN = candles.subList(fromIndex, candles.size());

        double sum = lastN.stream().mapToLong(Candle::getVolume).sum();
        return sum / lastN.size();
    }

    /**
     * Checks if a holding meets the price movement condition for a specific timeframe.
     * Condition: Current close changes by >= threshold percentage from previous period close
     * 
     * @param holding the portfolio holding
     * @param timeframe the timeframe to check (DAILY, WEEKLY, MONTHLY)
     * @return PriceMovementAlert if condition met, null otherwise
     */
    private PriceMovementAlert checkPriceMovementCondition(PortfolioHolding holding, String timeframe) {
        LocalDate today = LocalDate.now();
        String instrumentKey = holding.getInstrumentToken();

        // Fetch current period candle based on timeframe
        List<Candle> currentCandles = fetchCandlesForTimeframe(instrumentKey, timeframe, today);
        if (currentCandles.isEmpty()) {
            log.debug("No {} candles found for {} on {}", timeframe, holding.getTradingSymbol(), today);
            return null;
        }

        // Convert to single candle for the period
        Candle currentPeriod = toPeriodCandle(currentCandles);

        // Fetch previous period candle
        LocalDate previousDate = calculatePreviousPeriodDate(today, timeframe);
        List<Candle> previousCandles = fetchCandlesForTimeframe(instrumentKey, timeframe, previousDate);
        if (previousCandles.isEmpty()) {
            log.debug("No previous {} candles found for {} on {}", timeframe, holding.getTradingSymbol(), previousDate);
            return null;
        }

        Candle previousPeriod = toPeriodCandle(previousCandles);

        // Calculate price change percentage
        double priceChange = currentPeriod.getClose() - previousPeriod.getClose();
        double priceChangePercentage = (priceChange / previousPeriod.getClose()) * 100;

        // Check if absolute price change exceeds threshold
        if (Math.abs(priceChangePercentage) < priceMovementThreshold) {
            log.debug("{} {} - Price change {}% below threshold {}", 
                holding.getTradingSymbol(), timeframe, priceChangePercentage, priceMovementThreshold);
            return null;
        }

        // Condition met - create alert
        return PriceMovementAlert.builder()
                .symbol(holding.getTradingSymbol())
                .companyName(holding.getCompanyName())
                .currentClose(currentPeriod.getClose())
                .previousClose(previousPeriod.getClose())
                .priceChange(priceChange)
                .priceChangePercentage(priceChangePercentage)
                .timeframe(timeframe)
                .quantity(holding.getQuantity())
                .pnl(holding.getPnl())
                .broker("UPSTOX") // TODO: Update when broker info is added to PortfolioHolding
                .build();
    }

    /**
     * Calculates the date for the previous period based on timeframe.
     */
    private LocalDate calculatePreviousPeriodDate(LocalDate today, String timeframe) {
        return switch (timeframe) {
            case "DAILY" -> today.minusDays(1);
            case "WEEKLY" -> today.minusWeeks(1);
            case "MONTHLY" -> today.minusMonths(1);
            default -> today.minusDays(1);
        };
    }

    /**
     * Sends volume alerts via Telegram and Discord.
     */
    private void sendVolumeAlerts(List<VolumeAlert> alerts) {
        StringBuilder message = new StringBuilder();
        message.append("🚨 *Portfolio Volume Spike Alert*\n");
        message.append("━━━━━━━━━━━━━━━━━━━━\n");
        message.append("Volume spike detected across timeframes:\n\n");

        for (VolumeAlert alert : alerts) {
            message.append(String.format(
                "📈 *%s* (%s)\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🕐 Timeframe: %s | Broker: %s\n" +
                "🔴 Close: %.2f | Open: %.2f\n" +
                "📊 Volume: %,d (%.1fx avg of last %d)\n" +
                "📉 Day Change: %.2f (%.2f%%)\n" +
                "💼 Quantity: %d | P&L: %.2f\n" +
                "━━━━━━━━━━━━━━━━━━━━\n\n",
                alert.getSymbol(),
                alert.getCompanyName(),
                alert.getTimeframe(),
                alert.getBroker(),
                alert.getClosePrice(),
                alert.getOpenPrice(),
                alert.getCurrentVolume(),
                alert.getVolumeRatioTimeframe(),
                alert.getLookbackPeriod(),
                alert.getDayChange(),
                alert.getDayChangePercentage(),
                alert.getQuantity(),
                alert.getPnl()
            ));
        }

        message.append(String.format("Total volume alerts: %d", alerts.size()));

        telegramService.sendMessage(message.toString());
        discordService.sendMessage(message.toString());

        log.info("Sent {} volume spike alerts", alerts.size());
    }

    /**
     * Sends price movement alerts via Telegram and Discord.
     */
    private void sendPriceMovementAlerts(List<PriceMovementAlert> alerts) {
        StringBuilder message = new StringBuilder();
        message.append("📊 *Portfolio Price Movement Alert*\n");
        message.append("━━━━━━━━━━━━━━━━━━━━\n");
        message.append("Significant price movement detected:\n\n");

        for (PriceMovementAlert alert : alerts) {
            String direction = alert.getPriceChangePercentage() >= 0 ? "🟢" : "🔴";
            message.append(String.format(
                "%s *%s* (%s)\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🕐 Timeframe: %s | Broker: %s\n" +
                "💰 Current Close: %.2f\n" +
                "📉 Previous Close: %.2f\n" +
                "📊 Change: %.2f (%.2f%%)\n" +
                "💼 Quantity: %d | P&L: %.2f\n" +
                "━━━━━━━━━━━━━━━━━━━━\n\n",
                direction,
                alert.getSymbol(),
                alert.getCompanyName(),
                alert.getTimeframe(),
                alert.getBroker(),
                alert.getCurrentClose(),
                alert.getPreviousClose(),
                alert.getPriceChange(),
                alert.getPriceChangePercentage(),
                alert.getQuantity(),
                alert.getPnl()
            ));
        }

        message.append(String.format("Total price movement alerts: %d", alerts.size()));

        telegramService.sendMessage(message.toString());
        discordService.sendMessage(message.toString());

        log.info("Sent {} price movement alerts", alerts.size());
    }
}
