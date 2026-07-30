package com.trading.algo.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.CsvStrategySnapshot;
import com.trading.algo.fibostrategy.OpeningCandleStrategyService;
import com.trading.algo.repo.CsvStrategySnapshotRepository;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Controller to handle CSV file uploads for manual strategy scanning.
 * 
 * Uploads a CSV file containing stock symbols, applies both ORB and Fibonacci strategies,
 * filters results, and sends Telegram alerts.
 */
@Slf4j
@RestController
@RequestMapping("/api/csv-strategy")
@RequiredArgsConstructor
public class CsvStrategyController {

    private static final LocalTime C1_TIME_15M = LocalTime.of(9, 15);
    private static final LocalTime C2_TIME_15M = LocalTime.of(9, 30);
    private static final LocalTime C1_TIME_5M = LocalTime.of(9, 15);
    private static final LocalTime C2_TIME_5M = LocalTime.of(9, 20);

    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final OpeningCandleStrategyService fibonacciStrategy;
    private final TelegramService telegramService;
    private final CsvStrategySnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /**
     * Upload CSV files and apply both ORB and Fibonacci strategies.
     * Accepts multiple CSV files. First two files are expected to have SYMBOL column.
     * Third file (and any with OI data) should have "%CHNG IN OI" column for future enhancements.
     * 
     * POST /api/csv-strategy/scan
     * Content-Type: multipart/form-data
     * Body: files (array of CSV files)
     */
    @PostMapping("/scan")
    @Transactional
    public ResponseEntity<Map<String, Object>> scanCsvFile(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        LocalDate scanDate = date != null ? date : LocalDate.now();
        log.info("CSV Strategy Scan START - files={}, date={}", files.length, scanDate);

        try {
            // Parse all CSV files to extract symbols and OI data
            Set<String> symbols = new HashSet<>();
            Map<String, String> oiChangeData = new HashMap<>(); // Store %CHNG IN OI for future enhancements
            Set<String> oiFileSymbols = new HashSet<>(); // Track symbols from OI file separately

            for (int i = 0; i < files.length; i++) {
                MultipartFile file = files[i];
                log.info("Processing file {}/{}: {}", i + 1, files.length, file.getOriginalFilename());
                
                CsvParseResult result = parseCsvSymbolsWithOI(file);
                
                // Check if this file has OI data (it's the OI CSV file)
                boolean hasOiData = !result.oiChangeData().isEmpty();
                
                if (hasOiData) {
                    // This is the OI file - apply filtering
                    for (String symbol : result.symbols()) {
                        String oiChangeStr = result.oiChangeData().get(symbol);
                        
                        try {
                            double oiChange = parseOiPercentage(oiChangeStr);
                            
                            // Keep only if outside -5% to +5% range
                            if (oiChange > 5.0 || oiChange < -5.0) {
                                symbols.add(symbol);
                                oiChangeData.put(symbol, oiChangeStr);
                                oiFileSymbols.add(symbol);
                                log.info("OI file: {} kept ({}%)", symbol, oiChange);
                            } else {
                                log.info("OI file: {} excluded ({}% in -5 to +5 range)", symbol, oiChange);
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Could not parse OI percentage for {}: {}", symbol, oiChangeStr);
                            // Keep symbol if we can't parse the percentage
                            symbols.add(symbol);
                            oiChangeData.put(symbol, oiChangeStr);
                            oiFileSymbols.add(symbol);
                        }
                    }
                    log.info("File {} (OI file): {} symbols after filtering", i + 1, oiFileSymbols.size());
                } else {
                    // This is a regular CSV file - include all symbols
                    symbols.addAll(result.symbols());
                    log.info("File {}: parsed {} symbols (no OI filter applied)", 
                        i + 1, result.symbols().size());
                }
            }

            if (symbols.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "No symbols found in any CSV file",
                    "timestamp", LocalDateTime.now().toString()
                ));
            }

            log.info("Total unique symbols from all CSVs: {}", symbols.size());
            log.info("Symbols from OI file (after filtering): {}", oiFileSymbols.size());
            log.info("Symbols with OI change data: {}", oiChangeData.size());

            // Resolve symbols to instrument keys (CSV stocks are already F&O)
            List<String> symbolList = new ArrayList<>(symbols);
            Map<String, String> symbolKeyMap = instrumentMaster.resolveToInstrumentKeyMap(symbolList);
            log.info("Resolved {} symbols to instrument keys", symbolKeyMap.size());

            // Run Fibonacci strategy on 15-minute timeframe
            List<BacktestTrade> fibonacciSignals15m = runFibonacciStrategy(symbolKeyMap, scanDate, C1_TIME_15M, C2_TIME_15M);
            log.info("Fibonacci 15m strategy found {} signals", fibonacciSignals15m.size());

            // Run Fibonacci strategy on 5-minute timeframe
            List<BacktestTrade> fibonacciSignals5m = runFibonacciStrategy(symbolKeyMap, scanDate, C1_TIME_5M, C2_TIME_5M);
            log.info("Fibonacci 5m strategy found {} signals", fibonacciSignals5m.size());

            // Run ORB strategy
            OrbStrategyResult orbResult = runOrbStrategy(symbolKeyMap, scanDate);
            List<OrbSignal> orbSignals = orbResult.signals();
            Map<String, String> orbSkipped = orbResult.skipped();
            log.info("ORB strategy found {} signals, {} skipped", orbSignals.size(), orbSkipped.size());

            // Load previous snapshot for NEW tag comparison (on or before current date, ordered by date and save time)
            Optional<CsvStrategySnapshot> previousSnapshot = snapshotRepository.findFirstByScanDateLessThanEqualOrderByScanDateDescSavedAtDesc(scanDate);
            log.info("Previous snapshot found: {}, date: {}", previousSnapshot.isPresent(),
                previousSnapshot.map(s -> s.getScanDate().toString()).orElse("N/A"));

            // Send Telegram alert with NEW tags
            sendTelegramAlert(fibonacciSignals15m, fibonacciSignals5m, orbSignals, orbSkipped, scanDate, files.length, oiChangeData.size(), previousSnapshot);

            // Save current snapshot
            saveCurrentSnapshot(fibonacciSignals15m, fibonacciSignals5m, orbSignals, scanDate);

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "date", scanDate.toString(),
                "filesProcessed", files.length,
                "totalSymbolsParsed", symbols.size(),
                "symbolsWithOiData", oiChangeData.size(),
                "fibonacciSignals15m", fibonacciSignals15m.size(),
                "fibonacciSignals5m", fibonacciSignals5m.size(),
                "orbSignals", orbSignals.size(),
                "timestamp", LocalDateTime.now().toString()
            ));

        } catch (Exception e) {
            log.error("Error processing CSV file", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to process CSV: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

     /**
      * Parse CSV file to extract stock symbols and OI change data.
      * Looks for column named "SYMBOL" or "symbol" (case-insensitive).
      * Also looks for "%CHNG IN OI" column for future enhancements.
      */
     private CsvParseResult parseCsvSymbolsWithOI(MultipartFile file) throws Exception {
         Set<String> symbols = new HashSet<>();
         Map<String, String> oiChangeData = new HashMap<>();

         try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
             String headerLine = reader.readLine();
             if (headerLine == null) {
                 return new CsvParseResult(symbols, oiChangeData);
             }

             // Find column indices
             String[] headers = parseCsvLine(headerLine);
             int symbolIndex = -1;
             int oiChangeIndex = -1;

             for (int i = 0; i < headers.length; i++) {
                 String header = headers[i].trim();
                 log.debug("Header {}: '{}'", i, header);

                 if (header.equalsIgnoreCase("SYMBOL")) {
                     symbolIndex = i;
                 } else if (header.equalsIgnoreCase("%CHNG IN OI") ||
                            header.equalsIgnoreCase("%CHNG IN OI ") ||
                            header.equalsIgnoreCase("%chng in oi")) {
                     oiChangeIndex = i;
                 }
             }

             if (symbolIndex == -1) {
                 throw new IllegalArgumentException("CSV must contain a 'SYMBOL' column. Found headers: " +
                     String.join(", ", headers));
             }

             // Read data rows
             String line;
             while ((line = reader.readLine()) != null) {
                 String[] values = parseCsvLine(line);
                 if (values.length > symbolIndex) {
                     String symbol = values[symbolIndex].trim().toUpperCase();

                     if (!symbol.isEmpty()) {
                         symbols.add(symbol);

                         // Extract OI change data if column exists
                         if (oiChangeIndex != -1 && values.length > oiChangeIndex) {
                             String oiChange = values[oiChangeIndex].trim();
                             if (!oiChange.isEmpty()) {
                                 oiChangeData.put(symbol, oiChange);
                             }
                         }
                     }
                 }
             }
        }

        return new CsvParseResult(symbols, oiChangeData);
    }

    /**
     * Record to hold CSV parse results.
     */
    private record CsvParseResult(Set<String> symbols, Map<String, String> oiChangeData) {}

    /**
     * Parse a CSV line properly handling quoted fields.
     */
    private String[] parseCsvLine(String line) {
        // Remove BOM (Byte Order Mark) if present
        if (line.startsWith("\uFEFF")) {
            line = line.substring(1);
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        
        // Remove quotes from each field
        return result.stream()
            .map(field -> field.startsWith("\"") && field.endsWith("\"") 
                ? field.substring(1, field.length() - 1).trim() 
                : field.trim())
            .toArray(String[]::new);
    }

    /**
     * Parse OI percentage string to double value.
     * Handles formats like "5.2%", "-3.1%", "5.2", "-3.1", etc.
     */
    private double parseOiPercentage(String oiChangeStr) {
        String cleaned = oiChangeStr.trim();
        // Remove % sign if present
        if (cleaned.endsWith("%")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        // Remove any parentheses or other non-numeric characters (except minus sign and decimal point)
        cleaned = cleaned.replaceAll("[^0-9.-]", "");
        return Double.parseDouble(cleaned);
    }

    /**
     * Run Fibonacci strategy on the given symbols with specified C1 and C2 times.
     */
    private List<BacktestTrade> runFibonacciStrategy(Map<String, String> symbolKeyMap, LocalDate date, 
                                                     LocalTime c1Time, LocalTime c2Time) {
        CopyOnWriteArrayList<BacktestTrade> signals = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(10);

        try {
            List<CompletableFuture<Void>> futures = symbolKeyMap.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(() -> 
                    scanFibonacciSymbol(entry.getKey(), entry.getValue(), date, c1Time, c2Time, signals), pool))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.MINUTES); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        return signals;
    }

    /**
     * Scan a single symbol with Fibonacci strategy using specified C1 and C2 times.
     */
    private void scanFibonacciSymbol(String symbol, String instrumentKey, LocalDate date, 
                                     LocalTime c1Time, LocalTime c2Time,
                                     CopyOnWriteArrayList<BacktestTrade> signals) {
        try {
            List<Candle> candles = candleService.fetchDayCandles(instrumentKey, date);
            
            if (candles.isEmpty()) {
                log.debug("Fibonacci: {} - no candles", symbol);
                return;
            }

            boolean hasC1 = candles.stream()
                .anyMatch(c -> c.getTimestamp().toLocalTime().equals(c1Time));
            boolean hasC2 = candles.stream()
                .anyMatch(c -> c.getTimestamp().toLocalTime().equals(c2Time));

            if (!hasC1 || !hasC2) {
                log.debug("Fibonacci: {} - C1({}) or C2({}) not available", symbol, c1Time, c2Time);
                return;
            }

            Optional<BacktestTrade> trade = fibonacciStrategy.evaluate(symbol, date, candles);
            if (trade.isPresent()) {
                signals.add(trade.get());
                log.info("Fibonacci signal ({}): {} {}", 
                    (c2Time.equals(C2_TIME_15M) ? "15m" : "5m"), symbol, trade.get().getDirection());
            }

        } catch (Exception e) {
            log.error("Fibonacci scan error for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Run ORB strategy on the given symbols.
     * Returns both signals and skipped stocks (with reasons)
     */
    private OrbStrategyResult runOrbStrategy(Map<String, String> symbolKeyMap, LocalDate date) {
        List<OrbSignal> signals = new CopyOnWriteArrayList<>();
        Map<String, String> skipped = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(10);

        try {
            List<CompletableFuture<Void>> futures = symbolKeyMap.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(() -> 
                    scanOrbSymbol(entry.getKey(), entry.getValue(), date, signals, skipped), pool))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.MINUTES); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        return new OrbStrategyResult(signals, skipped);
    }

    /**
     * Scan a single symbol with ORB strategy.
     * Now includes Previous Day Level Buffer Filter (1% threshold)
     * Tracks both signals and skipped stocks
     */
    private void scanOrbSymbol(String symbol, String instrumentKey, LocalDate date, 
                               List<OrbSignal> signals, Map<String, String> skipped) {
        try {
            List<Candle> candles = candleService.fetchDayCandles(instrumentKey, date);
            Candle opening = findCandle(candles, C1_TIME_15M);
            Candle latest = latestClosedCandle(candles);

            if (opening == null || latest == null || latest.getTimestamp().toLocalTime().equals(C1_TIME_15M)) {
                return;
            }

            // Fetch previous day high and low for buffer filter
            Map<String, Double> prevDayLevels = fetchPreviousDayLevels(instrumentKey, date);
            double prevDayHigh = prevDayLevels.getOrDefault("high", 0.0);
            double prevDayLow = prevDayLevels.getOrDefault("low", 0.0);

            double rollingHigh = opening.getHigh();
            double rollingLow = opening.getLow();

            // BUY signal with buffer filter
            if (latest.getClose() > rollingHigh) {
                String skipReason = checkBuyBufferFilterWithReason(symbol, latest.getClose(), prevDayHigh);
                if (skipReason == null) {
                    signals.add(new OrbSignal(symbol, "BUY", latest.getClose(), rollingHigh, rollingLow,
                        latest.getTimestamp().toLocalTime()));
                    log.info("ORB signal: {} BUY at {}", symbol, latest.getClose());
                } else {
                    skipped.put(symbol + "_BUY", skipReason);
                }
            }
            // SELL signal with buffer filter
            else if (latest.getClose() < rollingLow) {
                String skipReason = checkSellBufferFilterWithReason(symbol, latest.getClose(), prevDayLow);
                if (skipReason == null) {
                    signals.add(new OrbSignal(symbol, "SELL", latest.getClose(), rollingLow, rollingHigh,
                        latest.getTimestamp().toLocalTime()));
                    log.info("ORB signal: {} SELL at {}", symbol, latest.getClose());
                } else {
                    skipped.put(symbol + "_SELL", skipReason);
                }
            }

        } catch (Exception e) {
            log.error("ORB scan error for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Check if BUY signal has sufficient buffer room to previous day high (1% threshold)
     * Returns null if signal passes, otherwise returns skip reason
     */
    private String checkBuyBufferFilterWithReason(String symbol, double currentPrice, double prevDayHigh) {
        if (prevDayHigh <= 0) {
            // No previous day data available, allow signal
            return null;
        }

        if (currentPrice > prevDayHigh) {
            // Already above prev day high — sufficient room
            return null;
        }

        // Calculate buffer room to previous day high
        double bufferRoom = (prevDayHigh - currentPrice) / currentPrice * 100.0;
        if (bufferRoom >= 1.0) {
            return null; // Signal passes
        }

        // Signal fails buffer check - return reason
        return String.format("BUY - Buffer: %.2f%% (need 1.0%% to prev high ₹%.2f)", bufferRoom, prevDayHigh);
    }

    /**
     * Check if SELL signal has sufficient buffer room to previous day low (1% threshold)
     * Returns null if signal passes, otherwise returns skip reason
     */
    private String checkSellBufferFilterWithReason(String symbol, double currentPrice, double prevDayLow) {
        if (prevDayLow <= 0) {
            // No previous day data available, allow signal
            return null;
        }

        if (currentPrice < prevDayLow) {
            // Already below prev day low — sufficient room
            return null;
        }

        // Calculate buffer room to previous day low
        double bufferRoom = (currentPrice - prevDayLow) / currentPrice * 100.0;
        if (bufferRoom >= 1.0) {
            return null; // Signal passes
        }

        // Signal fails buffer check - return reason
        return String.format("SELL - Buffer: %.2f%% (need 1.0%% to prev low ₹%.2f)", bufferRoom, prevDayLow);
    }

    /**
     * Fetch previous day's high and low for buffer filter
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> fetchPreviousDayLevels(String instrumentKey, LocalDate date) {
        Map<String, Double> result = new HashMap<>();
        result.put("high", 0.0);
        result.put("low", 0.0);

        try {
            String encodedKey = instrumentKey.replace("|", "%7C");
            LocalDate endDate = date;
            LocalDate fromDate = date.minusDays(5);

            java.net.URI uri = java.net.URI.create(String.format(
                "https://api.upstox.com/v3/historical-candle/%s/days/1/%s/%s",
                encodedKey, endDate, fromDate));

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null || !"success".equals(response.get("status"))) return result;

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) return result;

            List<List<Object>> candles = (List<List<Object>>) data.get("candles");
            if (candles == null || candles.size() < 2) return result;

            // candles[0] = today, candles[1] = previous completed day
            List<Object> prevDay = candles.get(1);
            // [0]=timestamp [1]=open [2]=high [3]=low [4]=close [5]=volume
            result.put("high", toDouble(prevDay.get(2)));
            result.put("low", toDouble(prevDay.get(3)));

        } catch (Exception e) {
            log.debug("Could not fetch previous day levels for {}: {}", instrumentKey, e.getMessage());
        }

        return result;
    }

    /**
     * Helper to convert object to double
     */
    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private Candle findCandle(List<Candle> candles, LocalTime time) {
        return candles.stream().filter(c -> c.getTimestamp().toLocalTime().equals(time)).findFirst().orElse(null);
    }

    private Candle latestClosedCandle(List<Candle> candles) {
        return candles.stream().max(Comparator.comparing(Candle::getTimestamp)).orElse(null);
    }

    /**
     * Send Telegram alert with strategy results.
     */
    private void sendTelegramAlert(List<BacktestTrade> fibonacciSignals15m, List<BacktestTrade> fibonacciSignals5m,
                                   List<OrbSignal> orbSignals, Map<String, String> orbSkipped, LocalDate date,
                                   int filesProcessed, int symbolsWithOiData,
                                   Optional<CsvStrategySnapshot> previousSnapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *CSV Strategy Scan Results*\n");
        sb.append("📅 ").append(date).append("\n");
        sb.append("📁 Files processed: ").append(filesProcessed).append("\n");
        if (symbolsWithOiData > 0) {
            sb.append("📈 Symbols with OI data: ").append(symbolsWithOiData).append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        // Extract previous symbols for NEW tag comparison
        Set<String> previousFib15mSymbols = extractPreviousSymbols(previousSnapshot, "fibonacci15mData");
        Set<String> previousFib5mSymbols = extractPreviousSymbols(previousSnapshot, "fibonacci5mData");
        Set<String> previousOrbSymbols = extractPreviousSymbols(previousSnapshot, "orbData");
        log.info("Previous symbols - Fib15m: {}, Fib5m: {}, ORB: {}",
            previousFib15mSymbols.size(), previousFib5mSymbols.size(), previousOrbSymbols.size());

        // Fibonacci 15m signals
        if (!fibonacciSignals15m.isEmpty()) {
            List<BacktestTrade> buys = fibonacciSignals15m.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.BUY)
                .collect(Collectors.toList());
            List<BacktestTrade> sells = fibonacciSignals15m.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.SELL)
                .collect(Collectors.toList());

            sb.append("🔢 *Fibonacci 15m Signals* (").append(fibonacciSignals15m.size()).append(")\n");

            if (!buys.isEmpty()) {
                sb.append("🟢 BUY (").append(buys.size()).append("): ");
                sb.append(formatSymbolsWithNewTag(buys.stream().map(BacktestTrade::getSymbol).collect(Collectors.toList()), previousFib15mSymbols));
                sb.append("\n");
            }

            if (!sells.isEmpty()) {
                sb.append("🔴 SELL (").append(sells.size()).append("): ");
                sb.append(formatSymbolsWithNewTag(sells.stream().map(BacktestTrade::getSymbol).collect(Collectors.toList()), previousFib15mSymbols));
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Fibonacci 5m signals
        if (!fibonacciSignals5m.isEmpty()) {
            List<BacktestTrade> buys = fibonacciSignals5m.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.BUY)
                .collect(Collectors.toList());
            List<BacktestTrade> sells = fibonacciSignals5m.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.SELL)
                .collect(Collectors.toList());

            sb.append("🔢 *Fibonacci 5m Signals* (").append(fibonacciSignals5m.size()).append(")\n");

            if (!buys.isEmpty()) {
                sb.append("🟢 BUY (").append(buys.size()).append("): ");
                sb.append(formatSymbolsWithNewTag(buys.stream().map(BacktestTrade::getSymbol).collect(Collectors.toList()), previousFib5mSymbols));
                sb.append("\n");
            }

            if (!sells.isEmpty()) {
                sb.append("🔴 SELL (").append(sells.size()).append("): ");
                sb.append(formatSymbolsWithNewTag(sells.stream().map(BacktestTrade::getSymbol).collect(Collectors.toList()), previousFib5mSymbols));
                sb.append("\n");
            }
            sb.append("\n");
        }

        // ORB signals
        if (!orbSignals.isEmpty()) {
            List<OrbSignal> buys = orbSignals.stream()
                .filter(s -> s.direction().equals("BUY"))
                .collect(Collectors.toList());
            List<OrbSignal> sells = orbSignals.stream()
                .filter(s -> s.direction().equals("SELL"))
                .collect(Collectors.toList());

            sb.append("📈 *ORB Signals* (").append(orbSignals.size()).append(")\n");

            if (!buys.isEmpty()) {
                sb.append("🟢 BUY (").append(buys.size()).append("): ");
                sb.append(formatSymbolsWithNewTag(buys.stream().map(OrbSignal::symbol).collect(Collectors.toList()), previousOrbSymbols));
                sb.append("\n");
            }

            if (!sells.isEmpty()) {
                sb.append("🔴 SELL (").append(sells.size()).append("): ");
                sb.append(formatSymbolsWithNewTag(sells.stream().map(OrbSignal::symbol).collect(Collectors.toList()), previousOrbSymbols));
                sb.append("\n");
            }
            sb.append("\n");
        }

        // ORB signals rejected by the previous-day buffer filter. Keep these in
        // the same Telegram alert so the filter's effect is visible to the user.
        if (!orbSkipped.isEmpty()) {
            sb.append("⏭️ *ORB Skipped by Previous-Day Buffer Filter* (")
                .append(orbSkipped.size()).append(")\n");
            orbSkipped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                String key = entry.getKey();
                String reason = entry.getValue();
                String symbol = key.replaceAll("_(BUY|SELL)$", "");
                sb.append("  ").append(symbol).append(": ").append(reason).append("\n");
            });
            sb.append("\n");
        }

        if (fibonacciSignals15m.isEmpty() && fibonacciSignals5m.isEmpty()
                && orbSignals.isEmpty() && orbSkipped.isEmpty()) {
            sb.append("_No signals found._\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Total Fibonacci 15m: ").append(fibonacciSignals15m.size()).append("\n");
        sb.append("Total Fibonacci 5m: ").append(fibonacciSignals5m.size()).append("\n");
        sb.append("Total ORB: ").append(orbSignals.size()).append("\n");
        if (!orbSkipped.isEmpty()) {
            sb.append("Skipped ORB (Previous-Day Buffer): ").append(orbSkipped.size()).append("\n");
        }

        telegramService.sendMessage(sb.toString());
        log.info("Telegram alert sent for CSV scan");
    }

    /**
     * Extract symbols from previous snapshot JSON data.
     */
    private Set<String> extractPreviousSymbols(Optional<CsvStrategySnapshot> previousSnapshot, String dataField) {
        if (previousSnapshot.isEmpty()) {
            return new HashSet<>();
        }

        try {
            String jsonData = switch (dataField) {
                case "fibonacci15mData" -> previousSnapshot.get().getFibonacci15mData();
                case "fibonacci5mData" -> previousSnapshot.get().getFibonacci5mData();
                case "orbData" -> previousSnapshot.get().getOrbData();
                default -> null;
            };

            log.info("Extracting from field: {}, jsonData: {}",
                dataField, jsonData == null ? "null" : (jsonData.isEmpty() ? "empty" : jsonData));

            if (jsonData == null || jsonData.isEmpty()) {
                return new HashSet<>();
            }

            Map<String, List<String>> data = objectMapper.readValue(jsonData, new TypeReference<>() {});
            Set<String> allSymbols = new HashSet<>();
            allSymbols.addAll(data.getOrDefault("BUY", List.of()));
            allSymbols.addAll(data.getOrDefault("SELL", List.of()));
            log.info("Extracted symbols from {}: {}", dataField, allSymbols);
            return allSymbols;
        } catch (Exception e) {
            log.warn("Failed to extract previous symbols from {}: {}", dataField, e.getMessage());
            return new HashSet<>();
        }
    }

    /**
     * Format symbols with (NEW) tag for symbols that weren't in the previous snapshot.
     */
    private String formatSymbolsWithNewTag(List<String> currentSymbols, Set<String> previousSymbols) {
        return currentSymbols.stream()
            .map(symbol -> previousSymbols.contains(symbol) ? symbol : symbol + " (NEW)")
            .collect(Collectors.joining(", "));
    }

    /**
     * Save current scan results as a snapshot.
     */
    private void saveCurrentSnapshot(List<BacktestTrade> fibonacciSignals15m, List<BacktestTrade> fibonacciSignals5m,
                                     List<OrbSignal> orbSignals, LocalDate scanDate) {
        try {
            // Delete existing snapshot for this date if exists
            if (snapshotRepository.existsByScanDate(scanDate)) {
                snapshotRepository.deleteByScanDate(scanDate);
            }

            // Prepare data for JSON serialization
            Map<String, List<String>> fib15mData = new HashMap<>();
            fib15mData.put("BUY", fibonacciSignals15m.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.BUY)
                .map(BacktestTrade::getSymbol)
                .collect(Collectors.toList()));
            fib15mData.put("SELL", fibonacciSignals15m.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.SELL)
                .map(BacktestTrade::getSymbol)
                .collect(Collectors.toList()));

            Map<String, List<String>> fib5mData = new HashMap<>();
            fib5mData.put("BUY", fibonacciSignals5m.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.BUY)
                .map(BacktestTrade::getSymbol)
                .collect(Collectors.toList()));
            fib5mData.put("SELL", fibonacciSignals5m.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.SELL)
                .map(BacktestTrade::getSymbol)
                .collect(Collectors.toList()));

            Map<String, List<String>> orbData = new HashMap<>();
            orbData.put("BUY", orbSignals.stream()
                .filter(s -> s.direction().equals("BUY"))
                .map(OrbSignal::symbol)
                .collect(Collectors.toList()));
            orbData.put("SELL", orbSignals.stream()
                .filter(s -> s.direction().equals("SELL"))
                .map(OrbSignal::symbol)
                .collect(Collectors.toList()));

            // Create and save snapshot
            CsvStrategySnapshot snapshot = CsvStrategySnapshot.builder()
                .scanDate(scanDate)
                .fibonacci15mData(objectMapper.writeValueAsString(fib15mData))
                .fibonacci5mData(objectMapper.writeValueAsString(fib5mData))
                .orbData(objectMapper.writeValueAsString(orbData))
                .savedAt(LocalDateTime.now())
                .build();

            snapshotRepository.save(snapshot);
            log.info("Saved CSV strategy snapshot for date: {}", scanDate);

        } catch (Exception e) {
            log.error("Failed to save CSV strategy snapshot: {}", e.getMessage());
        }
    }

    /**
     * Record for ORB signal.
     */
    private record OrbSignal(String symbol, String direction, double close, double level,
                             double stopReference, LocalTime time) {}

    /**
     * ORB scan output, including signals suppressed by the previous-day buffer filter.
     */
    private record OrbStrategyResult(List<OrbSignal> signals, Map<String, String> skipped) {}
}
