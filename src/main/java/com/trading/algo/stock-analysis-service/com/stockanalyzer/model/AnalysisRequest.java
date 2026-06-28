package com.stockanalyzer.model;


import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.Timeframe;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request body for POST /api/analysis/consolidation-breakout.
 * `candles` must be the full chronological series and include at least
 * 10 candles before consolidationEnd and 10 after it.
 */
public class AnalysisRequest {

    private String symbol;
    private Timeframe timeframe;
    private LocalDateTime consolidationStart;
    private LocalDateTime consolidationEnd;
    private List<Candle> candles;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public Timeframe getTimeframe() { return timeframe; }
    public void setTimeframe(Timeframe timeframe) { this.timeframe = timeframe; }
    public LocalDateTime getConsolidationStart() { return consolidationStart; }
    public void setConsolidationStart(LocalDateTime consolidationStart) { this.consolidationStart = consolidationStart; }
    public LocalDateTime getConsolidationEnd() { return consolidationEnd; }
    public void setConsolidationEnd(LocalDateTime consolidationEnd) { this.consolidationEnd = consolidationEnd; }
    public List<Candle> getCandles() { return candles; }
    public void setCandles(List<Candle> candles) { this.candles = candles; }
}
