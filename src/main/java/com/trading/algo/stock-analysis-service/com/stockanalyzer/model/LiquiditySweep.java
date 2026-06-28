package com.stockanalyzer.model;

import java.time.LocalDateTime;

public class LiquiditySweep {

    public enum Direction { UPSIDE, DOWNSIDE, NONE }

    private boolean swept;
    private Direction direction = Direction.NONE;
    private LocalDateTime sweepCandleTimestamp;
    private double sweepPrice;
    private boolean closedBackInside;
    private String description;

    public boolean isSwept() { return swept; }
    public void setSwept(boolean swept) { this.swept = swept; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public LocalDateTime getSweepCandleTimestamp() { return sweepCandleTimestamp; }
    public void setSweepCandleTimestamp(LocalDateTime t) { this.sweepCandleTimestamp = t; }
    public double getSweepPrice() { return sweepPrice; }
    public void setSweepPrice(double p) { this.sweepPrice = p; }
    public boolean isClosedBackInside() { return closedBackInside; }
    public void setClosedBackInside(boolean c) { this.closedBackInside = c; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
}
