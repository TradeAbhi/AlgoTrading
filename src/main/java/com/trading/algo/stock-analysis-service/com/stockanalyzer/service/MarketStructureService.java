package com.stockanalyzer.service;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.CandleWindow;
import com.stockanalyzer.model.MarketStructureResult;
import com.stockanalyzer.model.StructureShift;
import com.stockanalyzer.model.SwingPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads market structure (supply/demand) using simple 3-candle fractal swing
 * detection, classifies the prior trend going into the range, determines
 * whether the breakout is a Break of Structure (continuation / breakout from
 * a sideways range) or a Change of Character (reversal of an opposing trend),
 * and marks the origin (supply/demand) candle the move came from.
 */
@Service
public class MarketStructureService {

    public MarketStructureResult analyze(CandleWindow window) {
        MarketStructureResult result = new MarketStructureResult();

        List<SwingPoint> beforeSwings = detectSwingPoints(window.getBeforeWindow());
        String priorTrend = classifyTrend(beforeSwings);
        result.setPriorTrend(priorTrend);

        StructureShift shift = detectStructureShift(window, priorTrend);
        result.setStructureShift(shift);

        result.setSupplyDemandZoneDescription(identifyBaseZone(window, shift));

        List<SwingPoint> afterSwings = detectSwingPoints(window.getAfterWindow());
        result.setPostBreakoutTrend(classifyTrend(afterSwings));

        return result;
    }

    /** Simple fractal: a candle is a swing high/low if its high/low exceeds both immediate neighbors. */
    private List<SwingPoint> detectSwingPoints(List<Candle> candles) {
        List<SwingPoint> swings = new ArrayList<>();
        for (int i = 1; i < candles.size() - 1; i++) {
            Candle prev = candles.get(i - 1);
            Candle curr = candles.get(i);
            Candle next = candles.get(i + 1);

            if (curr.getHigh() > prev.getHigh() && curr.getHigh() > next.getHigh()) {
                swings.add(new SwingPoint(curr.getTimestamp(), curr.getHigh(), SwingPoint.Type.SWING_HIGH));
            }
            if (curr.getLow() < prev.getLow() && curr.getLow() < next.getLow()) {
                swings.add(new SwingPoint(curr.getTimestamp(), curr.getLow(), SwingPoint.Type.SWING_LOW));
            }
        }
        return swings;
    }

    private String classifyTrend(List<SwingPoint> swings) {
        List<Double> highs = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        for (SwingPoint sp : swings) {
            if (sp.getType() == SwingPoint.Type.SWING_HIGH) highs.add(sp.getPrice());
            else lows.add(sp.getPrice());
        }

        boolean higherHighs = isMonotonicIncreasing(highs);
        boolean higherLows = isMonotonicIncreasing(lows);
        boolean lowerHighs = isMonotonicDecreasing(highs);
        boolean lowerLows = isMonotonicDecreasing(lows);

        if (higherHighs && higherLows) return "UPTREND";
        if (lowerHighs && lowerLows) return "DOWNTREND";
        return "SIDEWAYS";
    }

    private boolean isMonotonicIncreasing(List<Double> values) {
        if (values.size() < 2) return false;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) <= values.get(i - 1)) return false;
        }
        return true;
    }

    private boolean isMonotonicDecreasing(List<Double> values) {
        if (values.size() < 2) return false;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) >= values.get(i - 1)) return false;
        }
        return true;
    }

    private StructureShift detectStructureShift(CandleWindow window, String priorTrend) {
        StructureShift shift = new StructureShift();
        Candle breakCandle = window.getStructuralBreakCandle();
        double rangeHigh = window.getRangeHigh();
        double rangeLow = window.getRangeLow();

        if (breakCandle == null) {
            shift.setType(StructureShift.Type.NONE);
            shift.setDescription("None of the post-consolidation candles closed beyond the range (" +
                    rangeLow + "-" + rangeHigh + ") - no confirmed structural break yet.");
            return shift;
        }

        boolean bullishBreak = breakCandle.getClose() > rangeHigh;

        // CHoCH only applies when reversing an established OPPOSING trend.
        // Breaking out of a SIDEWAYS range, or continuing an aligned trend, is BOS.
        boolean isReversal = (bullishBreak && priorTrend.equals("DOWNTREND")) ||
                              (!bullishBreak && priorTrend.equals("UPTREND"));

        if (bullishBreak) {
            shift.setType(isReversal ? StructureShift.Type.CHOCH_BULLISH : StructureShift.Type.BOS_BULLISH);
            shift.setBrokenLevel(rangeHigh);
            shift.setTimestamp(breakCandle.getTimestamp());
            shift.setDescription((isReversal ? "Change of Character (CHoCH): " : "Break of Structure (BOS): ") +
                    "price closed above the range high of " + rangeHigh + " at " + breakCandle.getTimestamp() +
                    ", confirming a bullish structural break" +
                    (isReversal ? " reversing the prior downtrend." : " (prior context: " + priorTrend.toLowerCase() + ")."));
        } else {
            shift.setType(isReversal ? StructureShift.Type.CHOCH_BEARISH : StructureShift.Type.BOS_BEARISH);
            shift.setBrokenLevel(rangeLow);
            shift.setTimestamp(breakCandle.getTimestamp());
            shift.setDescription((isReversal ? "Change of Character (CHoCH): " : "Break of Structure (BOS): ") +
                    "price closed below the range low of " + rangeLow + " at " + breakCandle.getTimestamp() +
                    ", confirming a bearish structural break" +
                    (isReversal ? " reversing the prior uptrend." : " (prior context: " + priorTrend.toLowerCase() + ")."));
        }
        return shift;
    }

    private String identifyBaseZone(CandleWindow window, StructureShift shift) {
        List<Candle> before = window.getBeforeWindow();
        boolean bullishBreak = shift.getType() == StructureShift.Type.BOS_BULLISH
                || shift.getType() == StructureShift.Type.CHOCH_BULLISH;
        boolean bearishBreak = shift.getType() == StructureShift.Type.BOS_BEARISH
                || shift.getType() == StructureShift.Type.CHOCH_BEARISH;

        if (!bullishBreak && !bearishBreak) {
            return "No directional break confirmed, so no demand/supply origin zone was marked.";
        }

        for (int i = before.size() - 1; i >= 0; i--) {
            Candle c = before.get(i);
            if (bullishBreak && !c.isBullish()) {
                return "Demand zone identified around " + c.getLow() + "-" + c.getHigh() +
                        " (last down-close candle before the move, at " + c.getTimestamp() +
                        ") - the origin from which buyers absorbed supply.";
            }
            if (bearishBreak && c.isBullish()) {
                return "Supply zone identified around " + c.getLow() + "-" + c.getHigh() +
                        " (last up-close candle before the move, at " + c.getTimestamp() +
                        ") - the origin from which sellers absorbed demand.";
            }
        }
        return "No clear opposite-colored base candle was found in the lookback window to mark an origin zone.";
    }
}
