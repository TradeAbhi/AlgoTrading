package com.trading.algo.ipo;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trading.algo.service.IpoMonitorService;
import com.trading.algo.service.IpoService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private final IpoRepository     ipoRepository;

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