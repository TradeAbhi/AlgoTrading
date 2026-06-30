package com.trading.algo.ipo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the NSE IPO CSV file and upserts EQ-type IPOs into the DB.
 *
 * CSV format (from NSE website — Market Data → IPO):
 *   COMPANY NAME, SECURITY TYPE, ISSUE PRICE, Symbol,
 *   ISSUE START DATE, ISSUE END DATE, PRICE RANGE, DATE OF LISTING
 *
 * Rules:
 *   - Only SECURITY TYPE = "EQ" rows are imported (skips SME, BE, IV etc.)
 *   - Upsert by company name — safe to re-upload same CSV multiple times
 *   - Issue price: uses the upper end of ISSUE PRICE column if it's a range,
 *     or direct value if single (e.g. "183" or "-" → null)
 *   - Dates: parsed as "d-MMM-yy" format (e.g. "12-May-26")
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoCsvImportService {

    // NSE uses multiple date formats across different CSV downloads:
    // Recent CSV  : "12-May-26"  (d-MMM-yy)
    // Historic CSV: "08-FEB-2016" (dd-MMM-yyyy uppercase)
    private static final List<DateTimeFormatter> DATE_FMTS = List.of(
            DateTimeFormatter.ofPattern("d-MMM-yy"),       // 12-May-26
            DateTimeFormatter.ofPattern("d-MMM-yyyy"),     // 8-FEB-2016
            DateTimeFormatter.ofPattern("dd-MMM-yyyy"),    // 08-FEB-2016
            DateTimeFormatter.ofPattern("d-MMMM-yyyy"),    // 8-February-2016
            DateTimeFormatter.ofPattern("yyyy-MM-dd")      // 2016-02-08
    );

    private final IpoRepository ipoRepository;

    // =========================================================================
    // Main import method
    // =========================================================================

    @Transactional
    public ImportResult importCsv(InputStream inputStream) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8));

        List<Ipo> importedIpos  = new ArrayList<>();
        List<String> imported  = new ArrayList<>();
        List<String> skipped   = new ArrayList<>();
        List<String> errors    = new ArrayList<>();

        String line;
        boolean headerSkipped = false;
        int lineNum = 0;

        while ((line = reader.readLine()) != null) {
            lineNum++;
            line = line.trim();

            // Skip header row
            if (!headerSkipped) {
                headerSkipped = true;
                log.info("CSV header: {}", line);
                continue;
            }

            if (line.isBlank()) continue;

            try {
                // Split by comma but respect quoted fields
                String[] cols = parseCsvLine(line);

                if (cols.length < 4) {
                    // Malformed / single-cell row (common in older NSE CSV format) — skip silently
                    log.debug("Line {} skipped — only {} columns", lineNum, cols.length);
                    continue;
                }
                // Pad missing columns with empty string so we don't get ArrayIndexOutOfBounds
                String[] padded = new String[8];
                java.util.Arrays.fill(padded, "");
                System.arraycopy(cols, 0, padded, 0, Math.min(cols.length, 8));
                cols = padded;

                String companyName   = clean(cols[0]);
                String securityType  = clean(cols[1]);
                String issuePriceRaw = clean(cols[2]);
                String symbol        = clean(cols[3]);
                String startDateRaw  = clean(cols[4]);
                String endDateRaw    = clean(cols[5]);
                String priceRange    = clean(cols[6]);
                String listingDateRaw= clean(cols[7]);

                // ── EQ filter ────────────────────────────────────────────────
                if (!"EQ".equalsIgnoreCase(securityType)) {
                    // Skip non-EQ types: SME, BE, IV, W1 (withdrawn), P1 etc.
                    log.debug("Skipping non-EQ: {} ({})", companyName, securityType);
                    continue;  // don't add to skipped list — keeps response clean
                }

                // ── Parse dates ──────────────────────────────────────────────
                LocalDate openDate    = parseDate(startDateRaw);
                LocalDate closeDate   = parseDate(endDateRaw);
                LocalDate listingDate = parseDate(listingDateRaw);

                // ── Parse issue price ────────────────────────────────────────
                Double issuePrice = parsePrice(issuePriceRaw);

                // ── Upsert by company name ───────────────────────────────────
                Ipo ipo = ipoRepository.findByName(companyName)
                        .orElseGet(Ipo::new);

                ipo.setName(companyName);
                ipo.setSymbol(symbol.isBlank() || symbol.equals("-") ? null : symbol.toUpperCase());
                ipo.setSecurityType(securityType);
                ipo.setIssuePrice(issuePrice);
                ipo.setPriceRange(priceRange.equals("-") ? null : priceRange);
                ipo.setOpenDate(openDate);
                ipo.setCloseDate(closeDate);
                ipo.setListingDate(listingDate);

                // Only set status if not already LISTED (don't overwrite listing data)
                if (ipo.getStatus() == null) {
                    LocalDate today = LocalDate.now();
                    if (listingDate != null && !listingDate.isAfter(today)) {
                        ipo.setStatus("LISTED");
                    } else if (openDate != null && !openDate.isAfter(today)) {
                        ipo.setStatus("OPEN");
                    } else {
                        ipo.setStatus("UPCOMING");
                    }
                }

                ipoRepository.save(ipo);
                importedIpos.add(ipo);
                imported.add(companyName + " (" + symbol + ")");
                log.info("Upserted IPO: {} | {} | listing: {}", companyName, symbol, listingDate);

            } catch (Exception e) {
                errors.add("Line " + lineNum + ": " + e.getMessage());
                log.warn("Error parsing line {} ({}): {}", lineNum, e.getMessage(),
                        line.length() > 80 ? line.substring(0, 80) + "..." : line);
            }
        }

        log.info("CSV import complete — imported={} errors={}",
                imported.size(), errors.size());

        return new ImportResult(importedIpos, imported, skipped, errors);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("-")) return null;
        // Try each format — NSE uses different formats in different CSV downloads
        for (DateTimeFormatter fmt : DATE_FMTS) {
            try {
                return LocalDate.parse(raw.trim(), fmt);
            } catch (Exception ignored) {}
        }
        // Last resort: try case-insensitive by normalising month to title case
        // e.g. "08-FEB-2016" → "08-Feb-2016"
        try {
            String[] parts = raw.trim().split("-");
            if (parts.length == 3) {
                String normalised = parts[0] + "-"
                        + parts[1].charAt(0) + parts[1].substring(1).toLowerCase() + "-"
                        + parts[2];
                return LocalDate.parse(normalised, DateTimeFormatter.ofPattern("d-MMM-yyyy"));
            }
        } catch (Exception ignored) {}
        log.warn("Could not parse date: '{}' — stored as null", raw);
        return null;
    }

    /**
     * Parses issue price — handles:
     *   "183"        → 183.0
     *   "-"          → null
     *   "Rs.47 to Rs.50" → 50.0 (upper end)
     *   ""           → null
     */
    private Double parsePrice(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("-")) return null;
        try {
            // Remove "Rs." prefix and commas
            String cleaned = raw.replaceAll("Rs\\.", "").replaceAll(",", "").trim();
            if (cleaned.contains("to")) {
                // Range — take upper end
                String[] parts = cleaned.split("to");
                return Double.parseDouble(parts[parts.length - 1].trim());
            }
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            log.warn("Could not parse price: '{}'", raw);
            return null;
        }
    }

    private String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("^\"|\"$", "");
    }

    /** Simple CSV line parser that handles quoted fields */
    private String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result.toArray(new String[0]);
    }

    // =========================================================================
    // Result DTO
    // =========================================================================

    public record ImportResult(
            List<Ipo> importedIpos,
            List<String> imported,
            List<String> skipped,
            List<String> errors
    ) {
        public int totalImported() { return imported.size(); }
        public int totalSkipped()  { return skipped.size(); }
        public int totalErrors()   { return errors.size(); }
    }
}