package com.stockanalyzer.model;

public class MarketStructureResult {

    private String priorTrend; // UPTREND / DOWNTREND / SIDEWAYS
    private StructureShift structureShift;
    private String supplyDemandZoneDescription;
    private String postBreakoutTrend;

    public String getPriorTrend() { return priorTrend; }
    public void setPriorTrend(String v) { this.priorTrend = v; }
    public StructureShift getStructureShift() { return structureShift; }
    public void setStructureShift(StructureShift v) { this.structureShift = v; }
    public String getSupplyDemandZoneDescription() { return supplyDemandZoneDescription; }
    public void setSupplyDemandZoneDescription(String v) { this.supplyDemandZoneDescription = v; }
    public String getPostBreakoutTrend() { return postBreakoutTrend; }
    public void setPostBreakoutTrend(String v) { this.postBreakoutTrend = v; }
}
