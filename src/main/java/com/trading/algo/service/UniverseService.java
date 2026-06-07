package com.trading.algo.service;

import com.trading.algo.config.WatchlistConfig;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Manages the F&O universe of Upstox instrument keys.
 *
 * Flow:
 *   NIFTY_FNO_SYMBOLS (trading symbols)
 *       → UpstoxInstrumentMasterService.resolveToInstrumentKeys()
 *       → validated "NSE_EQ|<ISIN>" keys
 *       → stored in universe list for watchlist scanning
 *
 * This guarantees every key in the universe is a real Upstox instrument key
 * and will never cause UDAPI1087.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UniverseService {

    private final WatchlistConfig watchlistConfig;
    private final UpstoxInstrumentMasterService instrumentMaster;

    private final List<String> universe = new CopyOnWriteArrayList<>();

    // =========================================================================
    // Complete Nifty F&O eligible stock list (NSE, Apr 2025)
    // Source: https://www.nseindia.com/products-services/equity-derivatives-list-underlyings-information
    // Update quarterly when NSE revises eligibility.
    // =========================================================================
    public static final List<String> NIFTY_FNO_SYMBOLS = List.of(

        // ── Nifty 50 ──────────────────────────────────────────────────────────
        "ADANIENT",   "ADANIPORTS",  "APOLLOHOSP",  "ASIANPAINT",  "AXISBANK",
        "BAJAJ-AUTO", "BAJFINANCE",  "BAJAJFINSV",  "BPCL",        "BHARTIARTL",
        "BRITANNIA",  "CIPLA",       "COALINDIA",   "DIVISLAB",    "DRREDDY",
        "EICHERMOT",  "GRASIM",      "HCLTECH",     "HDFCBANK",    "HDFCLIFE",
        "HEROMOTOCO", "HINDALCO",    "HINDUNILVR",  "ICICIBANK",   "ITC",
        "INDUSINDBK", "INFY",        "JSWSTEEL",    "KOTAKBANK",   "LT",
        "LTIM",       "M&M",         "MARUTI",      "NESTLEIND",   "NTPC",
        "ONGC",       "POWERGRID",   "RELIANCE",    "SBILIFE",     "SBIN",
        "SUNPHARMA",  "TATACONSUM",  "TATAMOTORS",  "TATASTEEL",   "TCS",
        "TECHM",      "TITAN",       "ULTRACEMCO",  "WIPRO",       "ZOMATO",

        // ── Nifty Next 50 ─────────────────────────────────────────────────────
        "ABB",        "AMBUJACEM",   "BANKBARODA",  "BEL",         "BERGEPAINT",
        "BHEL",       "BSE",         "CANBK",       "CHOLAFIN",    "COLPAL",
        "CONCOR",     "DLF",         "DMART",       "GAIL",        "GODREJCP",
        "GODREJPROP", "HAL",         "HAVELLS",     "HINDPETRO",   "IDFCFIRSTB",
        "INDHOTEL",   "INDIGO",      "INDUSTOWER",  "IOC",         "IRCTC",
        "IRFC",       "LICI",        "LUPIN",       "MARICO",      "MCDOWELL-N",
        "MOTHERSON",  "MPHASIS",     "NAUKRI",      "NHPC",        "NMDC",
        "OFSS",       "PAGEIND",     "PERSISTENT",  "PETRONET",    "PFC",
        "PIDILITIND", "PNB",         "RECLTD",      "SAIL",        "SHREECEM",
        "SIEMENS",    "TATAPOWER",   "TORNTPHARM",  "TRENT",       "TVSMOTOR",

        // ── Midcap / Smallcap F&O eligible ────────────────────────────────────
        "AARTIIND",   "ABCAPITAL",   "ABFRL",       "ACC",         "ADANIGREEN",
        "ADANIPOWER", "ALKEM",       "ANGELONE",    "AUROPHARMA",  "AUBANK",
        "BALKRISIND", "BANDHANBNK",  "BATAINDIA",   "BHARATFORG",  "BIOCON",
        "BOSCHLTD",   "CANFINHOME",  "CGPOWER",     "COROMANDEL",  "CUMMINSIND",
        "DABUR",      "DALBHARAT",   "DEEPAKNTR",   "DELHIVERY",   "DEVYANI",
        "ESCORTS",    "EXIDEIND",    "FEDERALBNK",  "FINEORG",     "FLUOROCHEM",
        "FORTIS",     "GLENMARK",    "GMRAIRPORT",  "GRANULES",    "GUJGASLTD",
        "HAPPSTMNDS", "HFCL",        "HINDCOPPER",  "HONAUT",      "ICICIGI",
        "ICICIPRULI", "IDBI",        "IEX",         "IGL",         "IPCALAB",
        "JBCHEPHARM", "JKCEMENT",    "JSWENERGY",   "JUBLFOOD",    "KALYANKJIL",
        "KFINTECH",   "KPITTECH",    "L&TFH",       "LALPATHLAB",  "LATENTVIEW",
        "LAURUSLABS", "LICHSGFIN",   "LUXIND",      "MANKIND",     "MASTEK",
        "MCX",        "METROPOLIS",  "MFSL",        "MRF",         "MUTHOOTFIN",
        "NATIONALUM", "NAVINFLUOR",  "NBCC",        "NCC",         "NLCINDIA",
        "OBEROIRLTY", "OIL",         "OLECTRA",     "PAYTM",       "PCBL",
        "PEL",        "PFIZER",      "PHOENIXLTD",  "PIIND",       "POLYCAB",
        "POLICYBZR",  "POONAWALLA",  "PVRINOX",     "RADICO",      "RAMCOCEM",
        "RITES",      "ROUTE",       "RPOWER",      "SJVN",        "SKFINDIA",
        "SOBHA",      "SOLARINDS",   "SPANDANA",    "SRF",         "SUPREMEIND",
        "SUNTV",      "SUZLON",      "SYNGENE",     "TANLA",       "TATACOMM",
        "TATAELXSI",  "TATATECH",    "TEAMLEASE",   "TIINDIA",     "TIMKEN",
        "TORNTPOWER", "TRIDENT",     "UBL",         "UCOBANK",     "UNIONBANK",
        "UPL",        "UTIAMC",      "VBL",         "VEDL",        "VOLTAS",
        "WHIRLPOOL",  "YESBANK",     "ZEEL",        "ZYDUSLIFE",   "CDSL",
        "CAMS",       "INDIAMART",   "JUSTDIAL",    "ATGL",        "APLAPOLLO",
        "CROMPTON",   "CYIENT",      "EMAMILTD",    "ENDURANCE",   "BAJAJELEC",
        "BSOFT",      "NIACL",       "RECLTD",      "SHRIRAMFIN"
    );

    // =========================================================================

    @PostConstruct
    public void init() {
        // Instrument master must already be loaded (it's a @PostConstruct too;
        // Spring initializes beans in dependency order so master loads first).
        loadUniverse();
    }

    /** Refresh daily at 7:00 AM IST — after instrument master refresh at 6:30 AM */
    @Scheduled(cron = "0 0 7 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshUniverse() {
        log.info("Refreshing F&O universe with latest instrument keys...");
        loadUniverse();
    }

    /** Returns validated Upstox instrument keys ready for market quote API calls */
    public List<String> getUniverse() {
        return Collections.unmodifiableList(universe);
    }

    /** Raw F&O trading symbols */
    public List<String> getFnoSymbols() {
        return Collections.unmodifiableList(NIFTY_FNO_SYMBOLS);
    }

    /** Check if a trading symbol is F&O eligible */
    public boolean isFnoEligible(String symbol) {
        return NIFTY_FNO_SYMBOLS.contains(symbol.toUpperCase())
            || NIFTY_FNO_SYMBOLS.contains(symbol);
    }

    // -------------------------------------------------------------------------

    private void loadUniverse() {
        List<String> symbolsToResolve;

        List<String> configured = watchlistConfig.getUniverse();
        if (configured != null && !configured.isEmpty()) {
            // Config override: extract symbols, filter to F&O only, then resolve
            symbolsToResolve = configured.stream()
                    .map(this::extractSymbol)
                    .filter(s -> isFnoEligible(s))
                    .distinct()
                    .collect(Collectors.toList());

            int skipped = configured.size() - symbolsToResolve.size();
            if (skipped > 0) {
                log.warn("Skipped {} non-F&O symbols from config universe", skipped);
            }
        } else {
            symbolsToResolve = NIFTY_FNO_SYMBOLS;
        }

        // Resolve trading symbols → real Upstox instrument keys via master
        List<String> resolvedKeys = instrumentMaster.resolveToInstrumentKeys(symbolsToResolve);

        universe.clear();
        universe.addAll(resolvedKeys);

        log.info("Universe ready: {}/{} F&O symbols resolved to instrument keys",
                resolvedKeys.size(), symbolsToResolve.size());
    }

    /**
     * Extracts the trading symbol from an instrument key or returns as-is.
     * "NSE_EQ|RELIANCE" → "RELIANCE"
     * "RELIANCE"         → "RELIANCE"
     */
    private String extractSymbol(String key) {
        return key.contains("|") ? key.split("\\|")[1] : key;
    }
}
/**
 * Manages the universe of instrument keys to scan.
 *
 * FILTER: Only Nifty F&O eligible stocks (~182 stocks as of Apr 2025).
 * Non-F&O stocks are completely excluded from all watchlist categories.
 *
 * NSE updates the F&O eligibility list quarterly.
 * Source: https://www.nseindia.com/products-services/equity-derivatives-list-underlyings-information
 *
 * Instrument key format used for Upstox:
 *   Equity quotes  → "NSE_EQ|<SYMBOL>"
 *   F&O OI data    → "NSE_FO|<SYMBOL>"
 */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class UniverseService {
//
//    private final WatchlistConfig watchlistConfig;
//
//    private final List<String> universe = new CopyOnWriteArrayList<>();
//
//    // =========================================================================
//    // Complete Nifty F&O eligible stock list (NSE, Apr 2025)
//    // =========================================================================
//    public static final List<String> NIFTY_FNO_SYMBOLS = List.of(
//
//        // ── Nifty 50 ──────────────────────────────────────────────────────────
//        "ADANIENT",   "ADANIPORTS",  "APOLLOHOSP",  "ASIANPAINT",  "AXISBANK",
//        "BAJAJ-AUTO", "BAJFINANCE",  "BAJAJFINSV",  "BPCL",        "BHARTIARTL",
//        "BRITANNIA",  "CIPLA",       "COALINDIA",   "DIVISLAB",    "DRREDDY",
//        "EICHERMOT",  "GRASIM",      "HCLTECH",     "HDFCBANK",    "HDFCLIFE",
//        "HEROMOTOCO", "HINDALCO",    "HINDUNILVR",  "ICICIBANK",   "ITC",
//        "INDUSINDBK", "INFY",        "JSWSTEEL",    "KOTAKBANK",   "LT",
//        "LTIM",       "M&M",         "MARUTI",      "NESTLEIND",   "NTPC",
//        "ONGC",       "POWERGRID",   "RELIANCE",    "SBILIFE",     "SBIN",
//        "SUNPHARMA",  "TATACONSUM",  "TATAMOTORS",  "TATASTEEL",   "TCS",
//        "TECHM",      "TITAN",       "ULTRACEMCO",  "WIPRO",       "ZOMATO",
//
//        // ── Nifty Next 50 ─────────────────────────────────────────────────────
//        "ABB",        "AMBUJACEM",   "BANKBARODA",  "BEL",         "BERGEPAINT",
//        "BHEL",       "BSE",         "CANBK",       "CHOLAFIN",    "COLPAL",
//        "CONCOR",     "DLF",         "DMART",       "GAIL",        "GODREJCP",
//        "GODREJPROP", "HAL",         "HAVELLS",     "HINDPETRO",   "IDFCFIRSTB",
//        "INDHOTEL",   "INDIGO",      "INDUSTOWER",  "IOC",         "IRCTC",
//        "IRFC",       "LICI",        "LUPIN",       "MARICO",      "MCDOWELL-N",
//        "MOTHERSON",  "MPHASIS",     "NAUKRI",      "NHPC",        "NMDC",
//        "OFSS",       "PAGEIND",     "PERSISTENT",  "PETRONET",    "PFC",
//        "PIDILITIND", "PNB",         "RECLTD",      "SAIL",        "SHREECEM",
//        "SIEMENS",    "TATAPOWER",   "TORNTPHARM",  "TRENT",       "TVSMOTOR",
//
//        // ── Midcap / Smallcap F&O eligible ────────────────────────────────────
//        "AARTIIND",   "ABCAPITAL",   "ABFRL",       "ACC",         "ADANIGREEN",
//        "ADANIPOWER", "ALKEM",       "ANGELONE",    "AUROPHARMA",  "AUBANK",
//        "BALKRISIND", "BANDHANBNK",  "BATAINDIA",   "BHARATFORG",  "BIOCON",
//        "BOSCHLTD",   "CANFINHOME",  "CGPOWER",     "COROMANDEL",  "CUMMINSIND",
//        "DABUR",      "DALBHARAT",   "DEEPAKNTR",   "DELHIVERY",   "DEVYANI",
//        "ESCORTS",    "EXIDEIND",    "FEDERALBNK",  "FINEORG",     "FLUOROCHEM",
//        "FORTIS",     "GLENMARK",    "GMRAIRPORT",  "GODREJPROP",  "GRANULES",
//        "GUJGASLTD",  "HAPPSTMNDS",  "HFCL",        "HINDCOPPER",  "HONAUT",
//        "ICICIGI",    "ICICIPRULI",  "IDBI",        "IEX",         "IGL",
//        "IPCALAB",    "JBCHEPHARM",  "JKCEMENT",    "JSWENERGY",   "JUBLFOOD",
//        "KALYANKJIL", "KFINTECH",    "KPITTECH",    "L&TFH",       "LALPATHLAB",
//        "LATENTVIEW", "LAURUSLABS",  "LICHSGFIN",   "LUXIND",      "MANKIND",
//        "MASTEK",     "MCX",         "METROPOLIS",  "MFSL",        "MRF",
//        "MUTHOOTFIN", "NATIONALUM",  "NAVINFLUOR",  "NBCC",        "NCC",
//        "NLCINDIA",   "OBEROIRLTY",  "OIL",         "OLECTRA",     "PAYTM",
//        "PCBL",       "PEL",         "PFIZER",      "PHOENIXLTD",  "PIIND",
//        "POLYCAB",    "POLICYBZR",   "POONAWALLA",  "PVRINOX",     "RADICO",
//        "RAMCOCEM",   "RITES",       "ROUTE",       "RPOWER",      "SEQUENT",
//        "SJVN",       "SKFINDIA",    "SOBHA",       "SOLARINDS",   "SPANDANA",
//        "SRF",        "STAR",        "SUPREMEIND",  "SUNTV",       "SUZLON",
//        "SYNGENE",    "TANLA",       "TATACOMM",    "TATAELXSI",   "TATATECH",
//        "TEAMLEASE",  "TIINDIA",     "TIMKEN",      "TORNTPOWER",  "TRIDENT",
//        "UBL",        "UCOBANK",     "UNIONBANK",   "UPL",         "UTIAMC",
//        "VBL",        "VEDL",        "VOLTAS",      "WHIRLPOOL",   "YESBANK",
//        "ZEEL",       "ZYDUSLIFE",   "CDSL",        "CAMS",        "INDIAMART",
//        "JUSTDIAL",   "ATGL",        "APLAPOLLO",   "CROMPTON",    "CYIENT",
//        "EMAMILTD",   "ENDURANCE",   "BAJAJELEC",   "BSOFT",       "NIACL"
//    );
//
//    // =========================================================================
//
//    @PostConstruct
//    public void init() {
//        loadUniverse();
//    }
//
//    /** Refresh daily at 08:00 IST */
//    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Kolkata")
//    public void refreshUniverse() {
//        log.info("Refreshing F&O universe...");
//        loadUniverse();
//    }
//
//    /** Full list as Upstox NSE_EQ instrument keys */
//    public List<String> getUniverse() {
//        return Collections.unmodifiableList(universe);
//    }
//
//    /** Raw symbol list (no exchange prefix) */
//    public List<String> getFnoSymbols() {
//        return Collections.unmodifiableList(NIFTY_FNO_SYMBOLS);
//    }
//
//    /** Check if a symbol is F&O eligible */
//    public boolean isFnoEligible(String symbol) {
//        return NIFTY_FNO_SYMBOLS.contains(symbol.toUpperCase());
//    }
//
//    // -------------------------------------------------------------------------
//
//    private void loadUniverse() {
//        List<String> configured = watchlistConfig.getUniverse();
//
//        if (configured != null && !configured.isEmpty()) {
//            // Config override: still enforce F&O-only filter
//            List<String> filtered = configured.stream()
//                    .filter(key -> isFnoEligible(extractSymbol(key)))
//                    .collect(Collectors.toList());
//
//            int skipped = configured.size() - filtered.size();
//            if (skipped > 0) {
//                log.warn("Skipped {} non-F&O symbols from config universe", skipped);
//            }
//
//            universe.clear();
//            universe.addAll(filtered);
//            log.info("Universe loaded from config (F&O filtered): {} instruments", filtered.size());
//
//        } else {
//            List<String> fnoKeys = NIFTY_FNO_SYMBOLS.stream()
//                    .map(symbol -> "NSE_EQ|" + symbol)
//                    .collect(Collectors.toList());
//
//            universe.clear();
//            universe.addAll(fnoKeys);
//            log.info("Default Nifty F&O universe loaded: {} instruments", universe.size());
//        }
//    }
//
//    /**
//     * Extracts bare symbol from an instrument key.
//     * "NSE_EQ|RELIANCE" → "RELIANCE"
//     * "RELIANCE"         → "RELIANCE"
//     */
//    private String extractSymbol(String key) {
//        return key.contains("|") ? key.split("\\|")[1] : key;
//    }
//}