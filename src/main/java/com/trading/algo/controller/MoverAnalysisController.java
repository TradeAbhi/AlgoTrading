package com.trading.algo.controller;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trading.algo.service.MoverAnalysisService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/mover-analysis")
@RequiredArgsConstructor
public class MoverAnalysisController {

    private final MoverAnalysisService moverAnalysisService;

    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> run(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {

        LocalDate targetDate = date != null ? date : LocalDate.now();
        log.info("POST /api/mover-analysis/run - date={}", targetDate);
        moverAnalysisService.analyse(targetDate);
        return ResponseEntity.ok(Map.of(
                "status", "Mover analysis complete",
                "date", targetDate.toString()
        ));
    }
}
