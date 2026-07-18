package com.trading.algo.upstox;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Downloads the Upstox NSE instruments master JSON (refreshed daily at 6 AM by Upstox)
 * and builds a fast lookup map: tradingSymbol -> instrumentKey
 *
 * WHY this is needed:
 *   Upstox instrument keys use ISIN, NOT the trading symbol.
 *   e.g. RELIANCE -> "NSE_EQ|INE002A01018"  (NOT "NSE_EQ|RELIANCE")
 *   Sending "NSE_EQ|RELIANCE" to the market quote API returns UDAPI1087.
 *
 * Master file URL: https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz
 * Refreshed by Upstox: daily ~6 AM IST
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpstoxInstrumentMasterService {

    private static final String NSE_MASTER_URL =
            "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz";

    private final ObjectMapper objectMapper;

    /**
     * tradingSymbol (uppercase) -> instrumentKey
     * e.g. "RELIANCE" -> "NSE_EQ|INE002A01018"
     *
     * Only NSE_EQ EQ-type instruments are indexed here.
     */
    private final Map<String, String> symbolToKeyMap = new ConcurrentHashMap<>();

    /**
     * tradingSymbol (uppercase) -> instrumentKey for F&O instruments
     * e.g. "RELIANCE" -> "NSE_FO|...futures contract key..."
     *
     * Only NSE_FO instruments are indexed here.
     */
    private final Map<String, String> fnoSymbolToKeyMap = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------

    @PostConstruct
    public void init() {
        loadInstrumentMaster();
    }

    /**
     * Refresh daily at 6:30 AM IST (30 min after Upstox refreshes their master).
     */
    @Scheduled(cron = "0 30 6 * * MON-FRI", zone = "Asia/Kolkata")
    public void refresh() {
        log.info("Refreshing Upstox instrument master...");
        loadInstrumentMaster();
    }

    /**
     * Returns the Upstox instrument key for a trading symbol.
     * Returns empty Optional if the symbol is not found in master.
     *
     * @param tradingSymbol e.g. "RELIANCE", "M&M", "BAJAJ-AUTO"
     */
    public Optional<String> getInstrumentKey(String tradingSymbol) {
        return Optional.ofNullable(symbolToKeyMap.get(tradingSymbol.toUpperCase()));
    }

    /**
     * Resolves a list of F&O symbols to valid Upstox instrument keys.
     * Symbols not found in the master are skipped with a WARN log.
     *
     * @param symbols list of trading symbols e.g. ["RELIANCE", "TCS"]
     * @return list of valid instrument keys e.g. ["NSE_EQ|INE002A01018", ...]
     */
    public List<String> resolveToInstrumentKeys(List<String> symbols) {
        List<String> keys   = new ArrayList<>();
        List<String> missed = new ArrayList<>();

        for (String symbol : symbols) {
            Optional<String> key = getInstrumentKey(symbol);
            if (key.isPresent()) {
                keys.add(key.get());
            } else {
                missed.add(symbol);
            }
        }

        if (!missed.isEmpty()) {
            log.warn("Upstox instrument master: {} symbols NOT found -> {}",
                    missed.size(), missed);
        }

        log.info("Resolved {}/{} symbols to instrument keys", keys.size(), symbols.size());
        return keys;
    }

    /**
     * Resolves symbols to a Map<symbol, instrumentKey> directly.
     * SAFER than resolveToInstrumentKeys (List) — no index misalignment
     * when some symbols are missing from the master.
     */
    public java.util.Map<String, String> resolveToInstrumentKeyMap(List<String> symbols) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        List<String> missed = new ArrayList<>();

        for (String symbol : symbols) {
            Optional<String> key = getInstrumentKey(symbol);
            if (key.isPresent()) {
                result.put(symbol, key.get());
            } else {
                missed.add(symbol);
            }
        }

        if (!missed.isEmpty()) {
            log.warn("Instrument master: {} symbols NOT found -> {}", missed.size(), missed);
        }
        log.info("Resolved {}/{} symbols to instrument keys", result.size(), symbols.size());
        return result;
    }

    /** Total symbols loaded in master */
    public int getMasterSize() {
        return symbolToKeyMap.size();
    }

    /**
     * Resolves symbols to F&O instrument keys.
     * Returns a map of symbol -> instrumentKey for symbols that have F&O instruments.
     *
     * @param symbols list of trading symbols e.g. ["RELIANCE", "TCS"]
     * @return map of symbol -> F&O instrument key (only for symbols with F&O instruments)
     */
    public java.util.Map<String, String> resolveToInstrumentKeyMapForFno(List<String> symbols) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        List<String> missed = new ArrayList<>();

        for (String symbol : symbols) {
            String key = fnoSymbolToKeyMap.get(symbol.toUpperCase());
            if (key != null && !key.isEmpty()) {
                result.put(symbol, key);
            } else {
                missed.add(symbol);
            }
        }

        if (!missed.isEmpty()) {
            log.debug("F&O instrument master: {} symbols NOT found -> {}", missed.size(), missed);
        }
        log.info("Resolved {}/{} symbols to F&O instrument keys", result.size(), symbols.size());
        return result;
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private void loadInstrumentMaster() {
        try {
            log.info("Downloading Upstox NSE instrument master from {}", NSE_MASTER_URL);

            // Use Java 11 HttpClient — no auth needed, this is a public .gz file
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NSE_MASTER_URL))
                    .header("Accept-Encoding", "gzip")
                    .GET()
                    .build();

            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                log.error("Failed to download instrument master: HTTP {}", response.statusCode());
                return;
            }

            InputStream body = response.body();
            // The file is gzip-compressed — unwrap it
            InputStream decompressed = new GZIPInputStream(body);

            JsonNode instruments = objectMapper.readTree(decompressed);

            Map<String, String> newMap = new HashMap<>();
            Map<String, String> newFnoMap = new HashMap<>();
            int total = 0, indexed = 0, fnoIndexed = 0;

            for (JsonNode instrument : instruments) {
                total++;
                String segment       = instrument.path("segment").asText("");
                String instrumentType= instrument.path("instrument_type").asText("");
                String tradingSymbol = instrument.path("trading_symbol").asText("").toUpperCase();
                String instrumentKey = instrument.path("instrument_key").asText("");

                // Index NSE_EQ equity instruments
                // instrument_type = "EQ" for normal equity
                // Skip BE (book entry), IL (institutional lot), etc.
                if ("NSE_EQ".equals(segment)
                        && "EQ".equals(instrumentType)
                        && !tradingSymbol.isEmpty()
                        && !instrumentKey.isEmpty()) {

                    newMap.put(tradingSymbol, instrumentKey);
                    indexed++;
                }

                // Index NSE_FO instruments (futures and options)
                // For F&O, we'll store the nearest expiry contract
                if ("NSE_FO".equals(segment)
                        && !tradingSymbol.isEmpty()
                        && !instrumentKey.isEmpty()) {

                    // Try to get the underlying symbol from the instrument data
                    String underlyingSymbol = instrument.path("underlying_symbol").asText("").toUpperCase();

                    // If underlying_symbol is available, use it directly
                    if (!underlyingSymbol.isEmpty()) {
                        // Store the first (nearest expiry) contract for each underlying symbol
                        if (!newFnoMap.containsKey(underlyingSymbol)) {
                            newFnoMap.put(underlyingSymbol, instrumentKey);
                            fnoIndexed++;
                            log.debug("F&O indexed: {} -> {} (from {}, underlying: {})",
                                    underlyingSymbol, instrumentKey, tradingSymbol, underlyingSymbol);
                        }
                    } else {
                        // Fallback: try to extract base symbol from trading symbol
                        String baseSymbol = extractBaseSymbolFromFno(tradingSymbol);
                        if (!baseSymbol.isEmpty()) {
                            if (!newFnoMap.containsKey(baseSymbol)) {
                                newFnoMap.put(baseSymbol, instrumentKey);
                                fnoIndexed++;
                                log.debug("F&O indexed (fallback): {} -> {} (from {})", baseSymbol, instrumentKey, tradingSymbol);
                            }
                        } else {
                            log.debug("F&O skipped - no underlying symbol and could not extract base from: {}", tradingSymbol);
                        }
                    }
                }
            }

            symbolToKeyMap.clear();
            symbolToKeyMap.putAll(newMap);

            fnoSymbolToKeyMap.clear();
            fnoSymbolToKeyMap.putAll(newFnoMap);

            log.info("Instrument master loaded: {} total records, {} NSE_EQ EQ indexed, {} NSE_FO indexed",
                    total, indexed, fnoIndexed);

        } catch (Exception e) {
            if (symbolToKeyMap.isEmpty()) {
                // Fatal on first load — no fallback available
                log.error("FATAL: Could not load Upstox instrument master on startup: {}", e.getMessage(), e);
            } else {
                // Keep stale map; log error but don't crash
                log.error("Failed to refresh instrument master (stale map retained): {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Extracts base symbol from F&O trading symbol.
     * Actual format from Upstox: "SYMBOL STRIKE TYPE DD MMM YY"
     * e.g., "CROMPTON 240 PE 29 SEP 26" -> "CROMPTON"
     * e.g., "BANKNIFTY 63800 CE 25 AUG 26" -> "BANKNIFTY"
     * e.g., "NIFTY 27000 CE 29 DEC 26" -> "NIFTY"
     */
    private String extractBaseSymbolFromFno(String fnoSymbol) {
        // The actual format is: SYMBOL STRIKE TYPE DD MMM YY
        // The base symbol is everything before the first space
        int spaceIndex = fnoSymbol.indexOf(' ');
        if (spaceIndex > 0) {
            return fnoSymbol.substring(0, spaceIndex);
        }
        return fnoSymbol;
    }
}