package com.stockanalyzer.service;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.CandleWindow;
import com.stockanalyzer.model.VolumeConfirmationResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Confirms (or casts doubt on) the breakout using volume: compares the volume
 * on the actual structural-break candle against the average volume during the
 * consolidation, and tracks whether volume was contracting (typical pre-breakout
 * accumulation) or expanding through the range.
 */
@Service
public class VolumeConfirmationService {

    private static final double CONFIRMATION_RATIO_THRESHOLD = 1.5;

    public VolumeConfirmationResult analyze(CandleWindow window) {
        VolumeConfirmationResult result = new VolumeConfirmationResult();

        List<Candle> rangeCandles = window.getRangeCandles();
        double avgVolume = rangeCandles.stream().mapToLong(Candle::getVolume).average().orElse(0);

        Candle refCandle = window.getStructuralBreakCandle() != null
                ? window.getStructuralBreakCandle()
                : window.getFirstReactionCandle();
        double breakoutVolume = refCandle.getVolume();
        double ratio = avgVolume == 0 ? 0 : breakoutVolume / avgVolume;

        result.setAvgVolumeDuringConsolidation(avgVolume);
        result.setBreakoutCandleVolume(breakoutVolume);
        result.setVolumeRatio(ratio);
        result.setVolumeConfirmed(ratio >= CONFIRMATION_RATIO_THRESHOLD);
        result.setVolumeTrendDuringConsolidation(classifyVolumeTrend(rangeCandles));

        String candleLabel = window.getStructuralBreakCandle() != null
                ? "structural break candle"
                : "first post-consolidation candle (no confirmed break yet)";

        if (result.isVolumeConfirmed()) {
            result.setVerdict(String.format(
                    "Volume on the %s was %.1fx the average consolidation volume - participation confirms the move.",
                    candleLabel, ratio));
        } else {
            result.setVerdict(String.format(
                    "Volume on the %s was only %.1fx the average consolidation volume - below the %.1fx confirmation threshold, raising fakeout risk.",
                    candleLabel, ratio, CONFIRMATION_RATIO_THRESHOLD));
        }

        return result;
    }

    private String classifyVolumeTrend(List<Candle> rangeCandles) {
        if (rangeCandles.size() < 4) return "FLAT";

        int mid = rangeCandles.size() / 2;
        double firstHalfAvg = rangeCandles.subList(0, mid).stream().mapToLong(Candle::getVolume).average().orElse(0);
        double secondHalfAvg = rangeCandles.subList(mid, rangeCandles.size()).stream().mapToLong(Candle::getVolume).average().orElse(0);

        if (firstHalfAvg == 0) return "FLAT";
        double change = (secondHalfAvg - firstHalfAvg) / firstHalfAvg;

        if (change <= -0.15) return "CONTRACTING";
        if (change >= 0.15) return "EXPANDING";
        return "FLAT";
    }
}
