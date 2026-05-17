package com.trading.algo.orb;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/orb")
@RequiredArgsConstructor
public class OrbController {

    private final OrbScannerServiceFinal orbScannerService;
    private final OrbStateStore stateStore;

    /** Manually trigger opening candle capture (for testing outside market hours) */
    @GetMapping("/capture")
    public ResponseEntity<Map<String, Object>> capture() {
        orbScannerService.triggerManualCapture();
        return ResponseEntity.ok(Map.of(
            "status", "done",
            "symbolsLoaded", stateStore.size()
        ));
    }

    /** Manually trigger a breakout scan cycle */
    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan() {
        orbScannerService.triggerManualScan();
        return ResponseEntity.ok(Map.of("status", "done"));
    }

    @GetMapping("/state")
    public ResponseEntity<List<Map<String, Object>>> state() {
        List<Map<String, Object>> result = stateStore.all().stream()
            .map(s -> {
                Map<String, Object> map = new java.util.HashMap<>();
                map.put("symbol", s.getSymbol());
                map.put("rollingHigh", s.getRollingHigh());
                map.put("rollingLow", s.getRollingLow());
                map.put("buyAlerted", s.isBuyAlerted());
                map.put("sellAlerted", s.isSellAlerted());
                return map;
            })
            .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(result);
    }
    
    @GetMapping("/watching")
    public ResponseEntity<List<Map<String, Object>>> watching() {
        List<Map<String, Object>> result = stateStore.all().stream()
            .filter(s -> !s.isBuyAlerted() && !s.isSellAlerted()) // both NOT triggered
            .map(s -> {
                Map<String, Object> map = new java.util.HashMap<>();
                map.put("symbol", s.getSymbol());
                map.put("rollingHigh", s.getRollingHigh());
                map.put("rollingLow", s.getRollingLow());
                map.put("buyAlerted", s.isBuyAlerted());
                map.put("sellAlerted", s.isSellAlerted());
                return map;
            })
            .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(result);
    }
}