package com.trading.algo.orb;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/** Manual ORB intervention for the current momentum-stock snapshot. */
@RestController
@RequestMapping("/api/orb")
@RequiredArgsConstructor
public class MomentumOrbController {

    private final MomentumOrbScannerService momentumOrbScannerService;

    /** POST /api/orb/momentum-scan */
    @PostMapping("/momentum-scan")
    public ResponseEntity<Map<String, Object>> scanMomentumSnapshot() {
        momentumOrbScannerService.triggerManualScan();
        return ResponseEntity.ok(Map.of(
                "status", "ORB scan triggered",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
