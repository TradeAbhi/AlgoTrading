package com.trading.algo.controller;

import com.trading.algo.service.IndexStrengthAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/index-strength")
@RequiredArgsConstructor
public class IndexStrengthController {

    private final IndexStrengthAlertService indexStrengthAlertService;

    @PostMapping("/alert")
    public ResponseEntity<Map<String, String>> sendAlert() {
        log.info("POST /api/index-strength/alert");
        indexStrengthAlertService.sendManualIndexStrengthAlert();
        return ResponseEntity.ok(Map.of("status", "Index strength alert sent"));
    }
}
