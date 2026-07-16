package com.stockmind.bootstrap;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.analysis.TechnicalAnalysisService;
import com.stockmind.application.market.MarketDataNotFoundException;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Technical indicator tools. Prefer the technical evidence snapshot unless a user explicitly requests one indicator.
 */
@Component
public class TechnicalIndicatorTools extends StockToolSupport {
    private static final Logger LOG = LoggerFactory.getLogger(TechnicalIndicatorTools.class);
    private static final String TECHNICAL_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string"},"start_date":{"type":"string","description":"yyyy-MM-dd"},"end_date":{"type":"string","description":"yyyy-MM-dd"},"adjustment":{"type":"string","enum":["NONE","FORWARD","BACKWARD"]},"fast_period":{"type":"integer"},"slow_period":{"type":"integer"},"signal_period":{"type":"integer"},"period":{"type":"integer"},"deviations":{"type":"number"}},"required":["symbol"]}
            """;
    private final TechnicalAnalysisService analysisService;

    public TechnicalIndicatorTools(TechnicalAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @AgentTool(name = "moving_average_analysis", title = "均线趋势分析", namespace = "finance.technical", category = "technical_analysis", tags = {"stock", "ma", "readonly"}, inputSchema = TECHNICAL_SCHEMA, description = "计算EMA快慢均线及交叉。")
    public String movingAverageAnalysis(Map<String, Object> in, String raw) {
        return run("moving_average_analysis", in, raw, "moving_average");
    }

    @AgentTool(name = "macd_analysis", title = "MACD分析", namespace = "finance.technical", category = "technical_analysis", tags = {"stock", "macd", "readonly"}, inputSchema = TECHNICAL_SCHEMA, description = "计算MACD的DIF、DEA和柱值。")
    public String macdAnalysis(Map<String, Object> in, String raw) {
        return run("macd_analysis", in, raw, "macd");
    }

    @AgentTool(name = "rsi_analysis", title = "RSI分析", namespace = "finance.technical", category = "technical_analysis", tags = {"stock", "rsi", "readonly"}, inputSchema = TECHNICAL_SCHEMA, description = "计算Wilder RSI。")
    public String rsiAnalysis(Map<String, Object> in, String raw) {
        return run("rsi_analysis", in, raw, "rsi");
    }

    @AgentTool(name = "bollinger_analysis", title = "布林带分析", namespace = "finance.technical", category = "technical_analysis", tags = {"stock", "bollinger", "readonly"}, inputSchema = TECHNICAL_SCHEMA, description = "计算BOLL中轨、轨道、带宽和%B。")
    public String bollingerAnalysis(Map<String, Object> in, String raw) {
        return run("bollinger_analysis", in, raw, "bollinger");
    }

    @AgentTool(name = "atr_analysis", title = "ATR波动分析", namespace = "finance.technical", category = "technical_analysis", tags = {"stock", "atr", "readonly"}, inputSchema = TECHNICAL_SCHEMA, description = "计算Wilder ATR和ATR%。ATR不表示方向。")
    public String atrAnalysis(Map<String, Object> in, String raw) {
        return run("atr_analysis", in, raw, "atr");
    }

    @AgentTool(name = "volume_analysis", title = "成交量与OBV分析", namespace = "finance.technical", category = "technical_analysis", tags = {"stock", "volume", "obv", "readonly"}, inputSchema = TECHNICAL_SCHEMA, description = "计算成交量均线、量比和OBV。")
    public String volumeAnalysis(Map<String, Object> in, String raw) {
        return run("volume_analysis", in, raw, "volume");
    }

    @AgentTool(name = "technical_indicator_snapshot", title = "技术指标快照", namespace = "finance.technical", category = "technical_evidence", tags = {"stock", "indicators", "readonly"}, inputSchema = TECHNICAL_SCHEMA, description = "核心技术证据工具。基于公式计算并返回均线、MACD、RSI、布林带、ATR、量能及机器可读信号；不输出投资建议。未明确单项指标时优先调用本工具。")
    public String technicalIndicatorSnapshot(Map<String, Object> in, String raw) {
        return run("technical_indicator_snapshot", in, raw, "snapshot");
    }

    private String run(String tool, Map<String, Object> input, String raw, String type) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail(tool, StockToolError.SYMBOL_REQUIRED);
        String start = asString(input.get("start_date")), end = asString(input.get("end_date")), adjustment = firstNonBlank(asString(input.get("adjustment")), "FORWARD");
        try {
            Object data = switch (type) {
                case "moving_average" ->
                        analysisService.movingAverage(symbol, start, end, adjustment, positiveInt(input.get("fast_period"), 20), positiveInt(input.get("slow_period"), 60));
                case "macd" ->
                        analysisService.macd(symbol, start, end, adjustment, positiveInt(input.get("fast_period"), 12), positiveInt(input.get("slow_period"), 26), positiveInt(input.get("signal_period"), 9));
                case "rsi" -> analysisService.rsi(symbol, start, end, adjustment, positiveInt(input.get("period"), 14));
                case "bollinger" ->
                        analysisService.bollinger(symbol, start, end, adjustment, positiveInt(input.get("period"), 20), positiveDouble(input.get("deviations"), 2));
                case "atr" -> analysisService.atr(symbol, start, end, adjustment, positiveInt(input.get("period"), 14));
                case "volume" ->
                        analysisService.volume(symbol, start, end, adjustment, positiveInt(input.get("period"), 20));
                default -> analysisService.technicalIndicatorSnapshot(symbol, start, end, adjustment);
            };
            return success(tool, data, "[\"symbol 非空\",\"指标结果包含数据来源与时间范围\",\"不包含买卖指令\"]");
        } catch (MarketDataNotFoundException e) {
            return fail(tool, StockToolError.MARKET_DATA_NOT_FOUND);
        } catch (IllegalArgumentException e) {
            return fail(tool, StockToolError.INVALID_TECHNICAL_PARAMETERS);
        } catch (Exception e) {
            LOG.warn("Business tool dependency failed, tool={}, exceptionType={}", tool, e.getClass().getName());
            LOG.debug("Business tool dependency failure details, tool={}", tool, e);
            return fail(tool, StockToolError.TECHNICAL_ANALYSIS_UNAVAILABLE);
        }
    }

}
