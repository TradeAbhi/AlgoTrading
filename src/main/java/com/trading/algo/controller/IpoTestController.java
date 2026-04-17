package com.trading.algo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trading.algo.service.IpoService;
import com.trading.algo.service.SchedulerService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/test/ipo")
@RequiredArgsConstructor
public class IpoTestController {

    private final IpoService ipoService;
private final SchedulerService schedulerService;
    @GetMapping("/sync")
    public String syncIpos() throws Exception {
        ipoService.syncIpos();
        return "IPO sync completed";
    }


    @GetMapping("/trigger-alerts")
    public String triggerAlerts() {
        schedulerService.ipoAlertScheduler();
        return "IPO alerts triggered";
    }
    
}