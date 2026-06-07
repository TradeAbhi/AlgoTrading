package com.trading.algo.dtos;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class OrbCandle {
    private String symbol;
    private String instrumentKey;
    private LocalDateTime candleTime;
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
}