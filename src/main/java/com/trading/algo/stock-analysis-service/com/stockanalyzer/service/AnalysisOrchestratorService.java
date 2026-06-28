package com.stockanalyzer.service;

import com.stockanalyzer.model.AnalysisConclusion;
import com.stockanalyzer.model.AnalysisResponse;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.CandleWindow;
import com.stockanalyzer.model.MarketStructureResult;
import com.stockanalyzer.model.OrderFlowResult;
import com.stockanalyzer.model.Timeframe;
import com.stockanalyzer.model.VolumeConfirmationResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Entry point for the whole service. Wire this bean into your own controller
 * (or call it from anywhere else in your app) - it ties together the three
 * analyzers and the conclusion synthesizer.
 *
 * Usage:
 *   AnalysisResponse response = orchestrator.analyze(
 *       "RELIANCE", Timeframe.FIFTEEN_MIN, fullCandleSeries,
 *       consolidationStartTimestamp, consolidationEndTimestamp);
 */
@Service
public class AnalysisOrchestratorService {

    private final CandleWindowExtractor windowExtractor;
    private final OrderFlowLiquidityService orderFlowService;
    private final MarketStructureService structureService;
    private final VolumeConfirmationService volumeService;
    private final ConclusionSynthesizerService conclusionService;

    public AnalysisOrchestratorService(CandleWindowExtractor windowExtractor,
                                        OrderFlowLiquidityService orderFlowService,
                                        MarketStructureService structureService,
                                        VolumeConfirmationService volumeService,
                                        ConclusionSynthesizerService conclusionService) {
        this.windowExtractor = windowExtractor;
        this.orderFlowService = orderFlowService;
        this.structureService = structureService;
        this.volumeService = volumeService;
        this.conclusionService = conclusionService;
    }

    public AnalysisResponse analyze(String symbol, Timeframe timeframe, List<Candle> candles,
                                     LocalDateTime consolidationStart, LocalDateTime consolidationEnd) {

        CandleWindow window = windowExtractor.extract(candles, consolidationStart, consolidationEnd);

        OrderFlowResult orderFlow = orderFlowService.analyze(window);
        MarketStructureResult structure = structureService.analyze(window);
        VolumeConfirmationResult volume = volumeService.analyze(window);
        AnalysisConclusion conclusion = conclusionService.synthesize(window, orderFlow, structure, volume);

        AnalysisResponse response = new AnalysisResponse();
        response.setSymbol(symbol);
        response.setTimeframe(timeframe);
        response.setRangeHigh(window.getRangeHigh());
        response.setRangeLow(window.getRangeLow());
        response.setOrderFlow(orderFlow);
        response.setMarketStructure(structure);
        response.setVolumeConfirmation(volume);
        response.setConclusion(conclusion);
        return response;
    }
}
