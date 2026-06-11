package com.trading.algo.delta.scheduler;

import com.trading.algo.delta.service.VolumeScannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeScannerScheduler {

    private final VolumeScannerService volumeScannerService;
    private final List<String>         monitoredSymbols;

    /** Runs at :00, :15, :30, :45 of every hour — same cadence as AlertScheduler */
    @Scheduled(cron = "${alert.scheduler.cron}")
    public void scanVolume() {
        log.info("=== Volume scan started at {} ===", Instant.now());
        for (String symbol : monitoredSymbols) {
            try {
                volumeScannerService.scan(symbol);
            } catch (Exception e) {
                log.error("Volume scan error for {}: {}", symbol, e.getMessage(), e);
            }
        }
        log.info("=== Volume scan completed ===");
    }
}
