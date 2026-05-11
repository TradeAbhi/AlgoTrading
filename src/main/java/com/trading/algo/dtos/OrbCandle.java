package com.trading.algo.dtos;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

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