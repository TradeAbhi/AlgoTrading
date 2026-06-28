package com.stockanalyzer.model;

public class VolumeConfirmationResult {

    private double avgVolumeDuringConsolidation;
    private double breakoutCandleVolume;
    private double volumeRatio;
    private boolean volumeConfirmed;
    private String volumeTrendDuringConsolidation; // CONTRACTING / EXPANDING / FLAT
    private String verdict;

    public double getAvgVolumeDuringConsolidation() { return avgVolumeDuringConsolidation; }
    public void setAvgVolumeDuringConsolidation(double v) { this.avgVolumeDuringConsolidation = v; }
    public double getBreakoutCandleVolume() { return breakoutCandleVolume; }
    public void setBreakoutCandleVolume(double v) { this.breakoutCandleVolume = v; }
    public double getVolumeRatio() { return volumeRatio; }
    public void setVolumeRatio(double v) { this.volumeRatio = v; }
    public boolean isVolumeConfirmed() { return volumeConfirmed; }
    public void setVolumeConfirmed(boolean v) { this.volumeConfirmed = v; }
    public String getVolumeTrendDuringConsolidation() { return volumeTrendDuringConsolidation; }
    public void setVolumeTrendDuringConsolidation(String v) { this.volumeTrendDuringConsolidation = v; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String v) { this.verdict = v; }
}
