package com.stockanalyzer.service;

import com.stockanalyzer.exception.InsufficientDataException;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.CandleWindow;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Slices a full candle series into the pieces the analyzers need:
 * - rangeCandles: everything between consolidationStart and consolidationEnd (defines support/resistance)
 * - beforeWindow: the 10 candles immediately leading up to the breakout point (consolidationEnd)
 * - afterWindow: the 10 candles immediately following the breakout point
 * - structuralBreakCandle: the first candle in afterWindow whose CLOSE actually clears the range
 *   (may differ from afterWindow's first candle if a liquidity sweep/wick happens first)
 */
@Service
public class CandleWindowExtractor {

    private static final int WINDOW_SIZE = 10;

    public CandleWindow extract(List<Candle> rawCandles, LocalDateTime consolidationStart, LocalDateTime consolidationEnd) {
        if (rawCandles == null || rawCandles.isEmpty()) {
            throw new InsufficientDataException("No candle data supplied.");
        }
        if (consolidationStart == null || consolidationEnd == null || !consolidationStart.isBefore(consolidationEnd)) {
            throw new InsufficientDataException("consolidationStart must be strictly before consolidationEnd.");
        }

        List<Candle> candles = new ArrayList<>(rawCandles);
        candles.sort(Comparator.comparing(Candle::getTimestamp));

        List<Candle> rangeCandles = new ArrayList<>();
        double rangeHigh = Double.NEGATIVE_INFINITY;
        double rangeLow = Double.POSITIVE_INFINITY;

        for (Candle c : candles) {
            if (!c.getTimestamp().isBefore(consolidationStart) && !c.getTimestamp().isAfter(consolidationEnd)) {
                rangeCandles.add(c);
                rangeHigh = Math.max(rangeHigh, c.getHigh());
                rangeLow = Math.min(rangeLow, c.getLow());
            }
        }

        if (rangeCandles.isEmpty()) {
            throw new InsufficientDataException("No candles found within the supplied consolidation range. Check your timestamps.");
        }

        int breakoutIndex = -1;
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).getTimestamp().isAfter(consolidationEnd)) {
                breakoutIndex = i;
                break;
            }
        }

        if (breakoutIndex == -1) {
            throw new InsufficientDataException("No candles found after consolidationEnd. Provide post-breakout candles too.");
        }
        if (breakoutIndex < WINDOW_SIZE) {
            throw new InsufficientDataException(
                    "Not enough candles before the breakout point - need at least " + WINDOW_SIZE +
                    " candles leading into consolidationEnd, found " + breakoutIndex + ".");
        }
        if (breakoutIndex + WINDOW_SIZE > candles.size()) {
            throw new InsufficientDataException(
                    "Not enough candles after the breakout point - need at least " + WINDOW_SIZE +
                    " candles following consolidationEnd, found " + (candles.size() - breakoutIndex) + ".");
        }

        List<Candle> beforeWindow = new ArrayList<>(candles.subList(breakoutIndex - WINDOW_SIZE, breakoutIndex));
        List<Candle> afterWindow = new ArrayList<>(candles.subList(breakoutIndex, breakoutIndex + WINDOW_SIZE));

        Candle structuralBreakCandle = null;
        for (Candle c : afterWindow) {
            if (c.getClose() > rangeHigh || c.getClose() < rangeLow) {
                structuralBreakCandle = c;
                break;
            }
        }

        CandleWindow window = new CandleWindow();
        window.setRangeCandles(rangeCandles);
        window.setRangeHigh(rangeHigh);
        window.setRangeLow(rangeLow);
        window.setBeforeWindow(beforeWindow);
        window.setAfterWindow(afterWindow);
        window.setFirstReactionCandle(afterWindow.get(0));
        window.setStructuralBreakCandle(structuralBreakCandle);
        return window;
    }
}
