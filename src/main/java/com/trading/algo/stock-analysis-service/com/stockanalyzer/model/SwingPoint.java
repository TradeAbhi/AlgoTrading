package com.stockanalyzer.model;

import java.time.LocalDateTime;

public class SwingPoint {

    public enum Type { SWING_HIGH, SWING_LOW }

    private final LocalDateTime timestamp;
    private final double price;
    private final Type type;

    public SwingPoint(LocalDateTime timestamp, double price, Type type) {
        this.timestamp = timestamp;
        this.price = price;
        this.type = type;
    }

    public LocalDateTime getTimestamp() { return timestamp; }
    public double getPrice() { return price; }
    public Type getType() { return type; }

    @Override
    public String toString() {
        return type + "@" + price + " (" + timestamp + ")";
    }
}
