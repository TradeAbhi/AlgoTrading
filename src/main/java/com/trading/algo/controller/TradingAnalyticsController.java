package com.trading.algo.controller;

import com.trading.algo.dtos.TradingDashboardDTO;
import com.trading.algo.entity.DailyTradingSummary;
import com.trading.algo.service.TradingAnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/trading")
public class TradingAnalyticsController {

    @Autowired
    private TradingAnalyticsService service;

    // Upload CSV
    @PostMapping("/upload")
    public String uploadCsv(@RequestParam("file") MultipartFile file) {
        try {
            service.processCsv(file);
            return "CSV processed successfully";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // Get analytics
    @GetMapping("/summary")
    public List<DailyTradingSummary> getSummary() {
      //  return service.getMonthlySummary();
    	
    	return service.findAll();
    }
    @GetMapping("/summary/by-month")
    public List<DailyTradingSummary> getSummary(
            @RequestParam int year,
            @RequestParam int month) {

        return service.getMonthlySummary(year, month);
    }
    
    @GetMapping("/dashboard")
    public TradingDashboardDTO getDashboard(
            @RequestParam int year,
            @RequestParam int month) {

        return service.getDashboard(year, month);
    }
    
    
    @PostMapping("/upload-pnl")
    public String uploadPnlCsv(@RequestParam("file") MultipartFile file) {
        try {
        	service.processPnlCsv(file);
            return "P&L CSV processed successfully";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }}
}
