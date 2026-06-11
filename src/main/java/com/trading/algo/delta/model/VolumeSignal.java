package com.trading.algo.delta.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class VolumeSignal {

    public enum Type { BREAKOUT, ABSORPTION, CLIMAX }

    private String     symbol;
    private Type       type;
    private BigDecimal currentVolume;
    private BigDecimal avgVolume;
    private BigDecimal volumeRatio;   // currentVolume / avgVolume
    private BigDecimal candleClose;
    private BigDecimal candleBody;    // abs(close - open)
    private BigDecimal candleRange;   // high - low
    private Instant    candleCloseTime;
}
