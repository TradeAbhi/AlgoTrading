package com.trading.algo.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads ind_nifty500list.csv (NSE format) and builds OrbConfig.keyToSymbolMap.
 *
 * Expected CSV columns (with header row):
 *   Company Name, Industry, Symbol, Series, ISIN Code
 *   col index:       0          1      2       3        4
 *
 * Instrument key is constructed as: "NSE_EQ|" + ISIN
 * Only rows where Series == "EQ" are loaded.
 *
 * Place file at: src/main/resources/ind_nifty500list.csv
 * Or set in application.yml:
 *   orb:
 *     instruments-csv-path: classpath:ind_nifty500list.csv
 */
@Component
public class OrbInstrumentLoader {

    private static final Logger log = LoggerFactory.getLogger(OrbInstrumentLoader.class);

    // Column indices in the NSE Nifty 500 CSV
    private static final int COL_SYMBOL = 2;
    private static final int COL_SERIES = 3;
    private static final int COL_ISIN   = 4;

    private final OrbConfig orbConfig;

    public OrbInstrumentLoader(OrbConfig orbConfig) {
        this.orbConfig = orbConfig;
    }

    @PostConstruct
    public void load() {
        String csvPath = orbConfig.getInstrumentsCsvPath();
        Map<String, String> keyToSymbol = new LinkedHashMap<>();

        try {
            BufferedReader reader = openReader(csvPath);
            if (reader == null) return;

            String line;
            boolean headerSkipped = false;
            int loaded = 0, skipped = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Skip header row
                if (!headerSkipped) {
                    headerSkipped = true;
                    log.debug("[ORB] CSV header: {}", line);
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length <= COL_ISIN) {
                    skipped++;
                    continue;
                }

                String symbol = parts[COL_SYMBOL].trim().toUpperCase();
                String series = parts[COL_SERIES].trim().toUpperCase();
                String isin   = parts[COL_ISIN].trim();

                // Only EQ series, valid ISIN (12 chars starting with IN)
                if (!"EQ".equals(series) || isin.length() != 12 || !isin.startsWith("IN")) {
                    skipped++;
                    continue;
                }

                String instrumentKey = "NSE_EQ|" + isin;
                keyToSymbol.put(instrumentKey, symbol);  // instrumentKey → symbol
                loaded++;
            }
            reader.close();

            orbConfig.setKeyToSymbolMap(keyToSymbol);
            log.info("[ORB] Loaded {} Nifty 500 instruments ({} skipped) from {}",
                loaded, skipped, csvPath);

            if (loaded == 0) {
                log.warn("[ORB] No instruments loaded — check CSV path and format. " +
                    "Expected columns: Company Name, Industry, Symbol, Series, ISIN Code");
            }

        } catch (Exception e) {
            log.error("[ORB] Failed to load CSV from '{}': {}", csvPath, e.getMessage(), e);
        }
    }

    private BufferedReader openReader(String csvPath) throws Exception {
        if (csvPath.startsWith("classpath:")) {
            String resource = csvPath.replace("classpath:", "");
            var stream = getClass().getClassLoader().getResourceAsStream(resource);
            if (stream == null) {
                log.warn("[ORB] CSV not found on classpath: '{}'. " +
                    "Place ind_nifty500list.csv in src/main/resources/ " +
                    "and set orb.instruments-csv-path=classpath:ind_nifty500list.csv", resource);
                return null;
            }
            return new BufferedReader(new InputStreamReader(stream));
        } else {
            return Files.newBufferedReader(Path.of(csvPath));
        }
    }
}