package com.stockanalyzer.service;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.CandleWindow;
import com.stockanalyzer.model.LiquiditySweep;
import com.stockanalyzer.model.LiquidityZone;
import com.stockanalyzer.model.OrderFlowResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Approximates order flow and identifies liquidity zones/sweeps from plain OHLCV data.
 *
 * NOTE ON METHODOLOGY: true order flow needs bid/ask tape (Level 2) data, which
 * isn't available for historical NSE/BSE pulls outside a live broker feed. This
 * proxies buy/sell pressure per candle from where the close sits within the
 * candle's high-low range, weighted by volume - a standard retail-tool approximation,
 * not raw tape data. Treat the delta numbers as directional signal, not literal volume.
 */
@Service
public class OrderFlowLiquidityService {

    private static final double EQUAL_LEVEL_TOLERANCE_PCT = 0.001; // 0.1% tolerance for "equal" highs/lows

    public OrderFlowResult analyze(CandleWindow window) {
        OrderFlowResult result = new OrderFlowResult();

        double deltaBefore = window.getBeforeWindow().stream().mapToDouble(this::candleDelta).sum();
        double deltaAfter = window.getAfterWindow().stream().mapToDouble(this::candleDelta).sum();

        Candle refCandle = window.getStructuralBreakCandle() != null
                ? window.getStructuralBreakCandle()
                : window.getFirstReactionCandle();
        double breakoutDelta = candleDelta(refCandle);

        result.setCumulativeDeltaBefore(deltaBefore);
        result.setCumulativeDeltaAfter(deltaAfter);
        result.setBreakoutCandleDelta(breakoutDelta);
        result.setLiquidityZones(detectLiquidityZones(window));
        result.setLiquiditySweep(detectLiquiditySweep(window));

        if (deltaAfter > 0 && breakoutDelta > 0) {
            result.setOrderFlowBias("BUY_DOMINANT");
        } else if (deltaAfter < 0 && breakoutDelta < 0) {
            result.setOrderFlowBias("SELL_DOMINANT");
        } else {
            result.setOrderFlowBias("NEUTRAL");
        }

        return result;
    }

    /**
     * Delta proxy: a close near the candle's high implies most volume traded
     * on the buy side, and vice versa for a close near the low.
     */
    private double candleDelta(Candle c) {
        double range = c.range();
        if (range == 0) {
            return 0; // flat/doji candle - no directional info
        }
        double buyRatio = (c.getClose() - c.getLow()) / range;
        double buyVolume = c.getVolume() * buyRatio;
        double sellVolume = c.getVolume() - buyVolume;
        return buyVolume - sellVolume;
    }

    private List<LiquidityZone> detectLiquidityZones(CandleWindow window) {
        List<LiquidityZone> zones = new ArrayList<>();
        zones.add(new LiquidityZone(LiquidityZone.Type.RANGE_HIGH, window.getRangeHigh(),
                "Resting buy-side liquidity above the range high - stops of short sellers and breakout buy orders"));
        zones.add(new LiquidityZone(LiquidityZone.Type.RANGE_LOW, window.getRangeLow(),
                "Resting sell-side liquidity below the range low - stops of long holders and breakdown sell orders"));

        List<Candle> rc = window.getRangeCandles();
        for (int i = 0; i < rc.size(); i++) {
            for (int j = i + 1; j < rc.size(); j++) {
                if (isApproxEqual(rc.get(i).getHigh(), rc.get(j).getHigh())) {
                    zones.add(new LiquidityZone(LiquidityZone.Type.EQUAL_HIGH, rc.get(i).getHigh(),
                            "Equal highs within the range - a frequently targeted liquidity pool"));
                }
                if (isApproxEqual(rc.get(i).getLow(), rc.get(j).getLow())) {
                    zones.add(new LiquidityZone(LiquidityZone.Type.EQUAL_LOW, rc.get(i).getLow(),
                            "Equal lows within the range - a frequently targeted liquidity pool"));
                }
            }
        }
        return zones;
    }

    private boolean isApproxEqual(double a, double b) {
        double diff = Math.abs(a - b);
        double tolerance = Math.max(a, b) * EQUAL_LEVEL_TOLERANCE_PCT;
        return diff <= tolerance;
    }

    /**
     * Looks for a stop-hunt style wick-and-reclaim in the first couple of
     * post-range candles: price pokes beyond the range to grab resting stops,
     * then closes back inside before the real directional move begins.
     */
    private LiquiditySweep detectLiquiditySweep(CandleWindow window) {
        LiquiditySweep sweep = new LiquiditySweep();
        double rangeHigh = window.getRangeHigh();
        double rangeLow = window.getRangeLow();

        List<Candle> after = window.getAfterWindow();
        int lookahead = Math.min(2, after.size());

        for (int i = 0; i < lookahead; i++) {
            Candle c = after.get(i);
            if (c.getLow() < rangeLow && c.getClose() > rangeLow) {
                sweep.setSwept(true);
                sweep.setDirection(LiquiditySweep.Direction.DOWNSIDE);
                sweep.setSweepCandleTimestamp(c.getTimestamp());
                sweep.setSweepPrice(c.getLow());
                sweep.setClosedBackInside(true);
                sweep.setDescription("Price wicked below the range low (" + rangeLow +
                        ") to grab sell-side liquidity, then closed back inside the range - a classic stop-hunt ahead of a move up.");
                return sweep;
            }
            if (c.getHigh() > rangeHigh && c.getClose() < rangeHigh) {
                sweep.setSwept(true);
                sweep.setDirection(LiquiditySweep.Direction.UPSIDE);
                sweep.setSweepCandleTimestamp(c.getTimestamp());
                sweep.setSweepPrice(c.getHigh());
                sweep.setClosedBackInside(true);
                sweep.setDescription("Price wicked above the range high (" + rangeHigh +
                        ") to grab buy-side liquidity, then closed back inside the range - a classic stop-hunt ahead of a move down.");
                return sweep;
            }
        }

        sweep.setDescription("No wick-and-reclaim liquidity sweep pattern detected in the candles immediately following the range.");
        return sweep;
    }
}
