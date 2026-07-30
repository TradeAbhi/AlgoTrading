package com.trading.algo.service;

import com.trading.algo.entity.WatchlistAlert;
import com.trading.algo.repo.WatchlistAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service to parse and store 15-minute watchlist alerts.
 * 
 * Parses the alert format to extract stock symbols from various categories:
 * - Multi-Category Stocks
 * - Top Gainers
 * - Top Losers
 * - Volume Shockers
 * - Active by Value
 * - High OI
 * - Only Buyers
 * - Only Sellers
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistAlertService {

    private final WatchlistAlertRepository repository;

    /**
     * Parse and store a watchlist alert
     */
    public WatchlistAlert processAlert(String rawAlert, LocalDateTime alertTime) {
        List<String> symbols = extractSymbols(rawAlert);
        
        WatchlistAlert alert = WatchlistAlert.builder()
                .alertTime(alertTime != null ? alertTime : LocalDateTime.now())
                .rawAlert(rawAlert)
                .symbols(symbols)
                .totalSymbols(symbols.size())
                .orbProcessed(false)
                .fiboProcessed(false)
                .build();
        
        WatchlistAlert saved = repository.save(alert);
        log.info("Watchlist alert saved with {} symbols at {}", symbols.size(), saved.getAlertTime());
        return saved;
    }

    /**
     * Extract stock symbols from the alert text
     * Pattern matches: SYMBOL (e.g., HFCL, HDFCBANK, INFY)
     */
    private List<String> extractSymbols(String alertText) {
        List<String> symbols = new ArrayList<>();
        
        // Pattern to match stock symbols (uppercase letters, 3-12 characters)
        // This matches typical NSE symbols like INFY, HDFCBANK, RELIANCE, etc.
        Pattern pattern = Pattern.compile("\\b[A-Z]{3,12}\\b");
        Matcher matcher = pattern.matcher(alertText);
        
        while (matcher.find()) {
            String symbol = matcher.group();
            // Filter out common words that might match the pattern
            if (!isCommonWord(symbol)) {
                symbols.add(symbol);
            }
        }
        
        // Deduplicate and return
        return symbols.stream()
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Filter out common words that might match the symbol pattern
     */
    private boolean isCommonWord(String word) {
        List<String> commonWords = List.of(
                "THE", "AND", "FOR", "ARE", "BUT", "NOT", "YOU", "ALL", "CAN", "HAD", "HER",
                "WAS", "ONE", "OUR", "OUT", "DAY", "GET", "HAS", "HIM", "HIS", "HOW", "MAN",
                "NEW", "NOW", "OLD", "SEE", "TWO", "WAY", "WHO", "BOY", "DID", "ITS", "LET",
                "PUT", "SAY", "SHE", "TOO", "USE", "DAD", "MOM", "VOL", "VAL", "HIGH", "LOW",
                "BUY", "SELL", "NEW", "TOP", "ONLY", "SCAN", "SCANNED", "STOCKS", "NONE",
                "PM", "AM", "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"
        );
        return commonWords.contains(word);
    }

    /**
     * Get the latest unprocessed alert for ORB strategy
     */
    public WatchlistAlert getLatestUnprocessedOrbAlert() {
        return repository.findFirstByOrbProcessedFalseOrderByAlertTimeDesc().orElse(null);
    }

    /**
     * Get the latest unprocessed alert for Fibonacci strategy
     */
    public WatchlistAlert getLatestUnprocessedFiboAlert() {
        return repository.findFirstByFiboProcessedFalseOrderByAlertTimeDesc().orElse(null);
    }

    /**
     * Mark an alert as processed by ORB
     */
    public void markOrbProcessed(Long alertId) {
        repository.findById(alertId).ifPresent(alert -> {
            alert.setOrbProcessed(true);
            repository.save(alert);
            log.info("Alert {} marked as ORB processed", alertId);
        });
    }

    /**
     * Mark an alert as processed by Fibonacci
     */
    public void markFiboProcessed(Long alertId) {
        repository.findById(alertId).ifPresent(alert -> {
            alert.setFiboProcessed(true);
            repository.save(alert);
            log.info("Alert {} marked as Fibonacci processed", alertId);
        });
    }

    /**
     * Get the latest alert (for manual trigger)
     */
    public WatchlistAlert getLatestAlert() {
        return repository.findFirstByOrderByAlertTimeDesc().orElse(null);
    }

    /**
     * Cleanup old alerts (older than specified days)
     */
    public void cleanupOldAlerts(int daysToKeep) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysToKeep);
        repository.deleteByAlertTimeBefore(cutoff);
        log.info("Cleaned up alerts older than {} days", daysToKeep);
    }
}
