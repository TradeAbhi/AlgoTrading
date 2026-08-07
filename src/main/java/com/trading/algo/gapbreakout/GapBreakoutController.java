package com.trading.algo.gapbreakout;

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
}
