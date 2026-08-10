package com.trading.algo.gapbreakout;

import com.trading.algo.service.UniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gap-breakout")
@RequiredArgsConstructor
public class GapBreakoutController {
    private final GapBreakoutService gapBreakoutService;

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan() {
        int alerts = gapBreakoutService.scanAndAlert();
        return ResponseEntity.ok(Map.of("status", "complete", "newAlerts", alerts));
    }

    @PostMapping("/scan/{universe}")
    public ResponseEntity<Map<String, Object>> scanUniverse(
            @PathVariable String universe) {
        GapBreakoutService.Universe universeEnum = GapBreakoutService.Universe.valueOf(universe.toUpperCase());
        int alerts = gapBreakoutService.scanAndAlert(universeEnum);
        return ResponseEntity.ok(Map.of("status", "complete", "newAlerts", alerts, "universe", universe));
    }

    @GetMapping("/backtest")
    public ResponseEntity<GapBreakoutService.BacktestReport> backtest(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "NIFTY_FNO") String universe) {
        GapBreakoutService.Universe universeEnum = GapBreakoutService.Universe.valueOf(universe.toUpperCase());
        return ResponseEntity.ok(gapBreakoutService.backtest(from, to, universeEnum));
    }

    @GetMapping("/backtest/{symbol}")
    public ResponseEntity<GapBreakoutService.BacktestReport> backtestSymbol(
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "NIFTY_FNO") String universe) {
        GapBreakoutService.Universe universeEnum = GapBreakoutService.Universe.valueOf(universe.toUpperCase());
        return ResponseEntity.ok(gapBreakoutService.backtestSymbol(symbol, from, to, universeEnum));
    }

    @GetMapping("/all/top10")
    public ResponseEntity<?> allTop10(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        Map<String, GapBreakoutService.BacktestReport> reports = new LinkedHashMap<>();

        for (String symbol : UniverseService.NIFTY_50_TOP_10) {
            GapBreakoutService.BacktestReport report =
                    gapBreakoutService.backtestSymbol(symbol, from, to, GapBreakoutService.Universe.NIFTY_50_TOP_10);
            reports.put(symbol, report);
        }

        return ResponseEntity.ok(reports);
    }

    @GetMapping("/all/fno")
    public ResponseEntity<?> allFno(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        Map<String, GapBreakoutService.BacktestReport> reports = new LinkedHashMap<>();

        for (String symbol : UniverseService.NIFTY_FNO_SYMBOLS) {
            GapBreakoutService.BacktestReport report =
                    gapBreakoutService.backtestSymbol(symbol, from, to, GapBreakoutService.Universe.NIFTY_FNO);
            reports.put(symbol, report);
        }

        return ResponseEntity.ok(reports);
    }
}
