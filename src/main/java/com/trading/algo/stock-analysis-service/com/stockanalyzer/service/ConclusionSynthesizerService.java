package com.stockanalyzer.service;

import com.stockanalyzer.model.AnalysisConclusion;
import com.stockanalyzer.model.CandleWindow;
import com.stockanalyzer.model.LiquiditySweep;
import com.stockanalyzer.model.MarketStructureResult;
import com.stockanalyzer.model.OrderFlowResult;
import com.stockanalyzer.model.StructureShift;
import com.stockanalyzer.model.VolumeConfirmationResult;
import org.springframework.stereotype.Service;

/**
 * Combines the three analyzers' outputs into a single rule-based scored
 * conclusion and a plain-English narrative describing what drove the price.
 */
@Service
public class ConclusionSynthesizerService {

    public AnalysisConclusion synthesize(CandleWindow window, OrderFlowResult orderFlow,
                                          MarketStructureResult structure, VolumeConfirmationResult volume) {
        AnalysisConclusion conclusion = new AnalysisConclusion();

        boolean bullish = structure.getStructureShift().getType() == StructureShift.Type.BOS_BULLISH
                || structure.getStructureShift().getType() == StructureShift.Type.CHOCH_BULLISH;
        boolean bearish = structure.getStructureShift().getType() == StructureShift.Type.BOS_BEARISH
                || structure.getStructureShift().getType() == StructureShift.Type.CHOCH_BEARISH;

        String direction = bullish ? "BULLISH" : bearish ? "BEARISH" : "NEUTRAL";
        conclusion.setDirection(direction);

        double score = 50;
        StringBuilder drivers = new StringBuilder();

        if (bullish || bearish) {
            score += 20;
            appendDriver(drivers, structure.getStructureShift().getType().name().startsWith("BOS")
                    ? "Break of Structure" : "Change of Character");
        }

        boolean sweepAligns = orderFlow.getLiquiditySweep().isSwept() &&
                ((bullish && orderFlow.getLiquiditySweep().getDirection() == LiquiditySweep.Direction.DOWNSIDE) ||
                 (bearish && orderFlow.getLiquiditySweep().getDirection() == LiquiditySweep.Direction.UPSIDE));
        if (sweepAligns) {
            score += 20;
            appendDriver(drivers, "a liquidity sweep");
        }

        boolean orderFlowAligns = (bullish && "BUY_DOMINANT".equals(orderFlow.getOrderFlowBias())) ||
                (bearish && "SELL_DOMINANT".equals(orderFlow.getOrderFlowBias()));
        if (orderFlowAligns) {
            score += 15;
            appendDriver(drivers, "aligned order-flow delta");
        }

        if (volume.isVolumeConfirmed()) {
            score += 15;
            appendDriver(drivers, "confirming volume expansion");
        } else {
            score -= 15;
        }

        score = Math.max(0, Math.min(100, score));
        conclusion.setConfidenceScore(score);
        conclusion.setPrimaryDriver(drivers.length() > 0 ? drivers.toString() : "No single dominant driver identified");
        conclusion.setNarrative(buildNarrative(direction, window, orderFlow, structure, volume, score));

        return conclusion;
    }

    private void appendDriver(StringBuilder sb, String text) {
        if (sb.length() > 0) sb.append(" + ");
        sb.append(text);
    }

    private String buildNarrative(String direction, CandleWindow window, OrderFlowResult orderFlow,
                                   MarketStructureResult structure, VolumeConfirmationResult volume, double score) {
        StringBuilder sb = new StringBuilder();

        if ("NEUTRAL".equals(direction)) {
            sb.append("Price has not produced a confirmed structural break out of the ")
              .append(window.getRangeLow()).append("-").append(window.getRangeHigh())
              .append(" consolidation within the candles examined. ");
        } else {
            sb.append("The move out of the ").append(window.getRangeLow()).append("-").append(window.getRangeHigh())
              .append(" consolidation was ").append(direction.toLowerCase()).append(". ");
        }

        sb.append(structure.getStructureShift().getDescription()).append(" ");
        sb.append(structure.getSupplyDemandZoneDescription()).append(" ");

        if (orderFlow.getLiquiditySweep().isSwept()) {
            sb.append(orderFlow.getLiquiditySweep().getDescription()).append(" ");
        } else {
            sb.append("No stop-hunt style liquidity sweep preceded the move, suggesting a more direct breakout. ");
        }

        sb.append("Order flow through the move was ")
          .append(orderFlow.getOrderFlowBias().toLowerCase().replace("_", " "))
          .append(", with a cumulative delta of ").append(String.format("%.0f", orderFlow.getCumulativeDeltaAfter()))
          .append(" across the post-breakout candles. ");

        sb.append(volume.getVerdict()).append(" ");
        sb.append("Volume during the consolidation itself was ")
          .append(volume.getVolumeTrendDuringConsolidation().toLowerCase())
          .append(", which is ")
          .append("CONTRACTING".equals(volume.getVolumeTrendDuringConsolidation())
                  ? "consistent with quiet accumulation/distribution before the move. "
                  : "a useful secondary read on conviction inside the range. ");

        sb.append("Overall confidence in this read: ").append(String.format("%.0f", score)).append("/100.");

        return sb.toString();
    }
}
