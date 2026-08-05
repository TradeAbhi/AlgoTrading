package com.trading.algo.controller;

import com.trading.algo.dtos.NiftyATRAnalysisResponse;
import com.trading.algo.service.NiftyATRAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * REST controller for Nifty ATR-based extreme day analysis.
 *
 * Endpoints:
 * - GET /api/nifty/atr-analysis - Analyze Nifty for most positive/negative days using ATR
 */
@Slf4j
@RestController
@RequestMapping("/api/nifty")
@RequiredArgsConstructor
public class NiftyATRAnalysisController {

    private final NiftyATRAnalysisService atrAnalysisService;

    /**
     * Analyzes Nifty for most positive and most negative days based on ATR multiples.
     *
     * Calculation:
     * - Very positive day = Close - Open > 2 × ATR(14)
     * - Very negative day = Open - Close > 2 × ATR(14)
     *
     * This approach automatically adapts to market volatility conditions.
     * For example:
     *   - In 2015 at Nifty 8000 (ATR ≈ 90): 180-point move = 2 ATR
     *   - In 2026 at Nifty 25000 (ATR ≈ 225): 450-point move = 2 ATR
     * Both are equally extreme.
     *
     * @param fromDate Start date (format: yyyy-MM-dd, required)
     * @param toDate End date (format: yyyy-MM-dd, required)
     * @return NiftyATRAnalysisResponse containing:
     *         - Most positive and most negative days
     *         - All extreme positive and negative days (sorted by severity)
     *         - ATR statistics and analysis details
     *
     * Example usage:
     * GET /api/nifty/atr-analysis?fromDate=2024-01-01&toDate=2024-12-31
     */
    @GetMapping("/atr-analysis")
    public ResponseEntity<NiftyATRAnalysisResponse> analyzeNiftyATRMovements(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate) {

        log.info("Received request to analyze Nifty ATR movements from {} to {}", fromDate, toDate);

        // Validate date range
        if (fromDate.isAfter(toDate)) {
            log.warn("Invalid date range: fromDate {} is after toDate {}", fromDate, toDate);
            return ResponseEntity.badRequest().build();
        }

        try {
            NiftyATRAnalysisResponse response = atrAnalysisService.analyzeNiftyExtremeMovements(fromDate, toDate);

            log.info("Analysis completed. Found {} extreme days: {} positive, {} negative",
                    response.getExtremeDaysCount(),
                    response.getAllPositiveDays().size(),
                    response.getAllNegativeDays().size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error analyzing Nifty ATR movements", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Alternative endpoint that accepts date parameters as path variables.
     *
     * Example usage:
     * GET /api/nifty/atr-analysis/2024-01-01/2024-12-31
     */
    @GetMapping("/atr-analysis/{fromDate}/{toDate}")
    public ResponseEntity<NiftyATRAnalysisResponse> analyzeNiftyATRMovementsByPath(
            @PathVariable
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @PathVariable
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate) {

        return analyzeNiftyATRMovements(fromDate, toDate);
    }
}

