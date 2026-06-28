package com.stockanalyzer.model;

public class AnalysisResponse {

    private String symbol;
    private Timeframe timeframe;
    private double rangeHigh;
    private double rangeLow;
    private OrderFlowResult orderFlow;
    private MarketStructureResult marketStructure;
    private VolumeConfirmationResult volumeConfirmation;
    private AnalysisConclusion conclusion;

    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public Timeframe getTimeframe() { return timeframe; }
    public void setTimeframe(Timeframe v) { this.timeframe = v; }
    public double getRangeHigh() { return rangeHigh; }
    public void setRangeHigh(double v) { this.rangeHigh = v; }
    public double getRangeLow() { return rangeLow; }
    public void setRangeLow(double v) { this.rangeLow = v; }
    public OrderFlowResult getOrderFlow() { return orderFlow; }
    public void setOrderFlow(OrderFlowResult v) { this.orderFlow = v; }
    public MarketStructureResult getMarketStructure() { return marketStructure; }
    public void setMarketStructure(MarketStructureResult v) { this.marketStructure = v; }
    public VolumeConfirmationResult getVolumeConfirmation() { return volumeConfirmation; }
    public void setVolumeConfirmation(VolumeConfirmationResult v) { this.volumeConfirmation = v; }
    public AnalysisConclusion getConclusion() { return conclusion; }
    public void setConclusion(AnalysisConclusion v) { this.conclusion = v; }
}
