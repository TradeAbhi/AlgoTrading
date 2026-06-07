package com.trading.algo.ipo;

import com.trading.algo.service.IpoMonitorService;
import com.trading.algo.service.IpoService;
import com.trading.algo.service.IpoStrategyMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * IPO REST controller.
 *
 * POST /api/ipo/sync                  → fetch latest IPOs from ipoalerts.in
 * POST /api/ipo/listing-open-alert    → send listing open performance to Telegram
 * POST /api/ipo/listing-eod-alert     → send listing EOD performance to Telegram
 * POST /api/ipo/upcoming-summary      → send upcoming listings summary to Telegram
 * GET  /api/ipo/all                   → get all IPOs from DB
 * GET  /api/ipo/upcoming              → get upcoming listings
 */
@Slf4j
@RestController
@RequestMapping("/api/ipo")
@RequiredArgsConstructor
public class IpoController {

    private final IpoService        ipoService;
    private final IpoMonitorService ipoMonitorService;
    private final IpoRepository       ipoRepository;
    private final IpoCsvImportService ipoCsvImportService;
    private final IpoStrategyMonitorService ipoStrategyMonitorService;

    /**
     * POST /api/ipo/upload-csv
     * Upload the NSE IPO CSV file — imports EQ-type IPOs only.
     * Safe to re-upload same file (upsert by company name).
     *
     * How to get the CSV:
     *   NSE website → Market Data → IPO → Download (.csv)
     *   URL: https://www.nseindia.com/market-data/all-upcoming-issues-ipo
     *
     * curl -X POST http://localhost:8080/api/ipo/upload-csv \
     *      -F "file=@/path/to/ipo_list.csv"
     */
    @PostMapping("/upload-csv")
    public ResponseEntity<Map<String, Object>> uploadCsv(
            @org.springframework.web.bind.annotation.RequestParam("file") MultipartFile file) {
        log.info("POST /api/ipo/upload-csv — file={} size={}",
                file.getOriginalFilename(), file.getSize());
        try {
            IpoCsvImportService.ImportResult result =
                    ipoCsvImportService.importCsv(file.getInputStream());
            return ResponseEntity.ok(java.util.Map.ofEntries(
                    java.util.Map.entry("imported",        result.totalImported()),
                    java.util.Map.entry("errors",          result.totalErrors()),
                    java.util.Map.entry("importedSymbols", result.imported()),
                    java.util.Map.entry("errorList",       result.errors())
            ));
        } catch (Exception e) {
            log.error("CSV upload failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** Sync latest IPOs from ipoalerts.in into DB */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, String>> sync() throws Exception {
        log.info("POST /api/ipo/sync");
        ipoService.syncIpos();
        return ResponseEntity.ok(Map.of("status", "IPO sync complete"));
    }

    /** Send listing open performance alert for today's listings */
    @PostMapping("/listing-open-alert")
    public ResponseEntity<Map<String, String>> listingOpenAlert() {
        log.info("POST /api/ipo/listing-open-alert");
        ipoMonitorService.sendListingOpenAlert();
        return ResponseEntity.ok(Map.of("status", "Listing open alert sent"));
    }

    /** Send EOD performance alert for today's listings */
    @PostMapping("/listing-eod-alert")
    public ResponseEntity<Map<String, String>> listingEodAlert() {
        log.info("POST /api/ipo/listing-eod-alert");
        ipoMonitorService.sendListingEodAlert();
        return ResponseEntity.ok(Map.of("status", "Listing EOD alert sent"));
    }

    /** Send upcoming listings summary (next 14 days) to Telegram */
    @PostMapping("/upcoming-summary")
    public ResponseEntity<Map<String, String>> upcomingSummary() {
        log.info("POST /api/ipo/upcoming-summary");
        ipoMonitorService.sendUpcomingListingsSummary();
        return ResponseEntity.ok(Map.of("status", "Upcoming summary sent"));
    }

    /** Scan past IPOs from the last 1 year for weekly breakout/reversal signals */
    @PostMapping("/strategy-scan")
    public ResponseEntity<Map<String, String>> strategyScan() {
        log.info("POST /api/ipo/strategy-scan");
        ipoStrategyMonitorService.scanAndAlert();
        return ResponseEntity.ok(Map.of("status", "IPO strategy scan complete"));
    }

    /** Get all IPOs stored in DB */
    @GetMapping("/all")
    public ResponseEntity<List<Ipo>> getAll() {
        return ResponseEntity.ok(ipoRepository.findAll());
    }

    /** Get upcoming listings (listing date >= today) */
    @GetMapping("/upcoming")
    public ResponseEntity<List<Ipo>> getUpcoming() {
        return ResponseEntity.ok(
            ipoRepository.findByListingDateAfterOrderByListingDateAsc(
                java.time.LocalDate.now().minusDays(1)
            )
        );
    }
}
