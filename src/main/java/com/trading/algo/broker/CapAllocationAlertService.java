package com.trading.algo.broker;

import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.PortfolioHolding;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Monitors small-cap and mid-cap allocation per broker.
 * Sends a Telegram alert if either exceeds 30% of total portfolio value.
 *
 * Classification:
 *   - Nifty500 symbols NOT in Nifty200 → Small Cap
 *   - Nifty200 symbols                 → Mid Cap (Nifty200 is the Nifty LargeMidcap200 index)
 *   - Everything else                  → Large Cap / Unknown
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapAllocationAlertService {

    private static final double MAX_ALLOCATION_PCT = 30.0;

    private final List<BrokerPortfolioService> brokerServices;
    private final com.trading.algo.upstox.UpstoxTokenService upstoxTokenService;
    private final TelegramService telegramService;

    private Set<String> nifty200Symbols  = new HashSet<>();
    private Set<String> smallCapSymbols  = new HashSet<>();

    @PostConstruct
    public void loadCapLists() {
        nifty200Symbols = loadSymbols("ind_nifty200list.csv");
        Set<String> nifty500Symbols = loadSymbols("ind_nifty500list.csv");
        smallCapSymbols = nifty500Symbols.stream()
                .filter(s -> !nifty200Symbols.contains(s))
                .collect(Collectors.toSet());
        log.info("Cap lists loaded — Nifty200: {}, SmallCap (500-200): {}",
                nifty200Symbols.size(), smallCapSymbols.size());
    }

    private Set<String> loadSymbols(String csvFile) {
        Set<String> symbols = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(csvFile).getInputStream()))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine || line.isBlank()) { firstLine = false; continue; }
                String[] parts = line.split(",");
                if (parts.length >= 3) symbols.add(parts[2].trim().toUpperCase());
            }
        } catch (Exception e) {
            log.error("Failed to load {}: {}", csvFile, e.getMessage());
        }
        return symbols;
    }

    public void checkAndAlert() {
        // Check if Upstox is authenticated before proceeding
        if (!upstoxTokenService.isAuthenticated()) {
            log.warn("Cap allocation check skipped - Upstox not authenticated");
            return;
        }

        for (BrokerPortfolioService broker : brokerServices) {
            if (!broker.isEnabled()) continue;
            try {
                List<PortfolioHolding> holdings = broker.fetchHoldings();
                if (holdings.isEmpty()) continue;
                checkBrokerAllocation(broker.getBrokerName(), holdings);
            } catch (Exception e) {
                log.error("Cap allocation check failed for {}: {}", broker.getBrokerName(), e.getMessage());
            }
        }
    }

    private void checkBrokerAllocation(String brokerName, List<PortfolioHolding> holdings) {
        double totalValue    = 0;
        double smallCapValue = 0;
        double midCapValue   = 0;

        List<String> smallCapStocks = new ArrayList<>();
        List<String> midCapStocks   = new ArrayList<>();

        for (PortfolioHolding h : holdings) {
            double value = h.getQuantity() * h.getLastPrice();
            totalValue += value;
            String symbol = h.getTradingSymbol().toUpperCase();

            if (smallCapSymbols.contains(symbol)) {
                smallCapValue += value;
                smallCapStocks.add(symbol);
            } else if (nifty200Symbols.contains(symbol)) {
                midCapValue += value;
                midCapStocks.add(symbol);
            }
        }

        if (totalValue == 0) return;

        double smallCapPct = (smallCapValue / totalValue) * 100;
        double midCapPct   = (midCapValue   / totalValue) * 100;

        boolean smallBreached = smallCapPct > MAX_ALLOCATION_PCT;
        boolean midBreached   = midCapPct   > MAX_ALLOCATION_PCT;

        log.info("[{}] SmallCap: {:.2f}% | MidCap: {:.2f}% | Total: ₹{:.0f}",
                brokerName, smallCapPct, midCapPct, totalValue);

        if (smallBreached || midBreached) {
            sendAlert(brokerName, totalValue, smallCapPct, midCapPct,
                    smallCapStocks, midCapStocks, smallBreached, midBreached);
        }
    }

    private void sendAlert(String brokerName, double totalValue,
                           double smallCapPct, double midCapPct,
                           List<String> smallCapStocks, List<String> midCapStocks,
                           boolean smallBreached, boolean midBreached) {

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ *Cap Allocation Breach — ").append(brokerName).append("*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append(String.format("💼 Total Portfolio: ₹%.0f%n", totalValue));
        sb.append(String.format("📊 Limit: %.0f%% per cap category%n%n", MAX_ALLOCATION_PCT));

        if (smallBreached) {
            sb.append(String.format("🔴 *Small Cap: %.2f%%* (limit %.0f%%)%n", smallCapPct, MAX_ALLOCATION_PCT));
            sb.append("Stocks: ").append(String.join(", ", smallCapStocks)).append("\n\n");
        }
        if (midBreached) {
            sb.append(String.format("🔴 *Mid Cap: %.2f%%* (limit %.0f%%)%n", midCapPct, MAX_ALLOCATION_PCT));
            sb.append("Stocks: ").append(String.join(", ", midCapStocks)).append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("⚡ Rebalance recommended.");

        telegramService.sendMessageToHoldings(sb.toString());
        log.warn("Cap allocation breach alert sent for {}", brokerName);
    }
}
