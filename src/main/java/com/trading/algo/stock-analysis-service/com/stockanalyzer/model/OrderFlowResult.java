package com.stockanalyzer.model;

import java.util.List;

public class OrderFlowResult {

    private double cumulativeDeltaBefore;
    private double cumulativeDeltaAfter;
    private double breakoutCandleDelta;
    private List<LiquidityZone> liquidityZones;
    private LiquiditySweep liquiditySweep;
    private String orderFlowBias; // BUY_DOMINANT / SELL_DOMINANT / NEUTRAL

    public double getCumulativeDeltaBefore() { return cumulativeDeltaBefore; }
    public void setCumulativeDeltaBefore(double v) { this.cumulativeDeltaBefore = v; }
    public double getCumulativeDeltaAfter() { return cumulativeDeltaAfter; }
    public void setCumulativeDeltaAfter(double v) { this.cumulativeDeltaAfter = v; }
    public double getBreakoutCandleDelta() { return breakoutCandleDelta; }
    public void setBreakoutCandleDelta(double v) { this.breakoutCandleDelta = v; }
    public List<LiquidityZone> getLiquidityZones() { return liquidityZones; }
    public void setLiquidityZones(List<LiquidityZone> v) { this.liquidityZones = v; }
    public LiquiditySweep getLiquiditySweep() { return liquiditySweep; }
    public void setLiquiditySweep(LiquiditySweep v) { this.liquiditySweep = v; }
    public String getOrderFlowBias() { return orderFlowBias; }
    public void setOrderFlowBias(String v) { this.orderFlowBias = v; }
}
