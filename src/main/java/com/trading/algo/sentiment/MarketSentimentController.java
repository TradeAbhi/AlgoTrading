package com.trading.algo.sentiment;


import com.trading.algo.service.MarketSentimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sentiment")
@RequiredArgsConstructor
public class MarketSentimentController {

    private final MarketSentimentService sentimentService;

    // ✅ Test Morning Snapshot
    @GetMapping("/morning")
    public String triggerMorningSnapshot() {
        sentimentService.morningSentimentAlert();
        return "✅ Morning sentiment alert triggered";
    }

    // ✅ Test Midday Snapshot
    @GetMapping("/midday")
    public String triggerMiddaySnapshot() {
        sentimentService.middaySentimentAlert();
        return "✅ Midday sentiment alert triggered";
    }

    // ✅ Test Pre-close Snapshot
    @GetMapping("/preclose")
    public String triggerPreClose() {
        sentimentService.preCloseSentimentAlert();
        return "✅ Pre-close sentiment alert triggered";
    }

    // ✅ Test End-of-Day Summary
    @GetMapping("/eod")
    public String triggerEOD() {
        sentimentService.eodSentimentSummary();
        return "✅ EOD sentiment summary triggered";
    }

    // ✅ Test PCR Alert
    @GetMapping("/pcr-alert")
    public String triggerPCRAlert() {
        sentimentService.pcrExtremeAlert();
        return "✅ PCR alert checked";
    }

    // ✅ Test VIX Alert
    @GetMapping("/vix-alert")
    public String triggerVIXAlert() {
        sentimentService.vixSpikeAlert();
        return "✅ VIX alert checked";
    }

    // ✅ Test Breadth Alert
    @GetMapping("/breadth-alert")
    public String triggerBreadthAlert() {
        sentimentService.breadthExtremeAlert();
        return "✅ Breadth alert checked";
    
    }
}