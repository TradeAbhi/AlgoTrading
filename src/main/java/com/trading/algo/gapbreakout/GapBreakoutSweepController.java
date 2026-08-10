package com.trading.algo.gapbreakout;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/gap-breakout")
@RequiredArgsConstructor
public class GapBreakoutSweepController {

    private final GapBreakoutParameterSweepEngine sweepEngine;

    /** Parameter sweep - NIFTY Top 10 only. */
    @GetMapping("/sweep/top10")
    public ResponseEntity<?> sweepTop10(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int topN) {

        GapBreakoutParameterSweepEngine.SweepResult result =
                sweepEngine.runSweep(from, to, GapBreakoutEngineService.Universe.NIFTY_50_TOP_10);

        List<GapBreakoutParameterSweepEngine.SweepSummaryRow> summary =
                sweepEngine.toSummary(result, topN);

        return ResponseEntity.ok(summary);
    }

    /** Parameter sweep - full Nifty 50 gap-breakout analysis universe. */
    @GetMapping("/sweep/nifty50")
    public ResponseEntity<?> sweepNifty50(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int topN) {

        GapBreakoutParameterSweepEngine.SweepResult result =
                sweepEngine.runSweep(from, to, GapBreakoutEngineService.Universe.NIFTY_50_GAP_BREAKOUT);

        List<GapBreakoutParameterSweepEngine.SweepSummaryRow> summary =
                sweepEngine.toSummary(result, topN);

        return ResponseEntity.ok(summary);
    }

    /** Parameter sweep - full NIFTY F&O universe. */
    @GetMapping("/sweep/fno")
    public ResponseEntity<?> sweepFno(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int topN) {

        GapBreakoutParameterSweepEngine.SweepResult result =
                sweepEngine.runSweep(from, to, GapBreakoutEngineService.Universe.NIFTY_FNO);

        List<GapBreakoutParameterSweepEngine.SweepSummaryRow> summary =
                sweepEngine.toSummary(result, topN);

        return ResponseEntity.ok(summary);
    }
}
