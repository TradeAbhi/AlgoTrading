package com.stockanalyzer.model;

public class LiquidityZone {

    public enum Type { RANGE_HIGH, RANGE_LOW, EQUAL_HIGH, EQUAL_LOW }

    private final Type type;
    private final double price;
    private final String description;

    public LiquidityZone(Type type, double price, String description) {
        this.type = type;
        this.price = price;
        this.description = description;
    }

    public Type getType() { return type; }
    public double getPrice() { return price; }
    public String getDescription() { return description; }
}
