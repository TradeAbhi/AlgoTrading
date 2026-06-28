package com.stockanalyzer.model;

public class AnalysisConclusion {

    private String direction; // BULLISH / BEARISH / NEUTRAL
    private String primaryDriver;
    private double confidenceScore;
    private String narrative;

    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }
    public String getPrimaryDriver() { return primaryDriver; }
    public void setPrimaryDriver(String v) { this.primaryDriver = v; }
    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double v) { this.confidenceScore = v; }
    public String getNarrative() { return narrative; }
    public void setNarrative(String v) { this.narrative = v; }
}
