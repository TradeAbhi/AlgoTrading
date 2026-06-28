package com.stockanalyzer.model;

import java.time.LocalDateTime;

public class StructureShift {

    public enum Type { BOS_BULLISH, BOS_BEARISH, CHOCH_BULLISH, CHOCH_BEARISH, NONE }

    private Type type = Type.NONE;
    private double brokenLevel;
    private LocalDateTime timestamp;
    private String description;

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public double getBrokenLevel() { return brokenLevel; }
    public void setBrokenLevel(double brokenLevel) { this.brokenLevel = brokenLevel; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
