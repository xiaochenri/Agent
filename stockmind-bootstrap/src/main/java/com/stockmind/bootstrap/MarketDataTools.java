package com.stockmind.bootstrap;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.analysis.TechnicalAnalysisService;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Market facts and raw OHLCV retrieval tools.
 */
@Component
public class MarketDataTools extends StockToolSupport {
    private static final String QUOTE_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^(\\\\d{6}|[A-Z]{1,5})$"},"end_date":{"type":"string","format":"date"},"adjustment":{"type":"string","enum":["NONE","FORWARD","BACKWARD"]}},"required":["symbol"],"additionalProperties":false}
            """;
    private static final String QUOTE_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"tool":{"type":"string","enum":["market_quote"]},"status":{"type":"string","enum":["success","failed"]},"validation_passed":{"type":"boolean"},"validation_rules":{"type":"array","items":{"type":"string"}},"validation_errors":{"type":"array","items":{"type":"string"}},"retryable":{"type":"boolean"},"error_code":{"type":"string"},"data":{"type":"object","properties":{"symbol":{"type":"string"},"dataset_id":{"type":"string"},"source":{"type":"string"},"as_of":{"type":"string"},"start_at":{"type":"string"},"end_at":{"type":"string"},"interval":{"type":"string"},"adjustment":{"type":"string"},"warnings":{"type":"array"},"price":{"type":"number"},"change_pct":{"type":"number"},"volume":{"type":"number"}},"required":["symbol","source","as_of","price","change_pct","volume"]},"metadata":{"type":"object"}},"required":["tool","status","validation_passed","validation_rules","validation_errors","retryable","error_code","data","metadata"],"additionalProperties":false}
            """;
    private static final String BARS_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string"},"start_date":{"type":"string","description":"yyyy-MM-dd"},"end_date":{"type":"string","description":"yyyy-MM-dd"},"adjustment":{"type":"string","enum":["NONE","FORWARD","BACKWARD"]}},"required":["symbol"]}
            """;
    private final TechnicalAnalysisService analysisService;

    public MarketDataTools(TechnicalAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @AgentTool(name = "market_quote", title = "股票行情查询", namespace = "finance.market", category = "market_data", tags = {"stock", "quote", "readonly"}, inputSchema = QUOTE_SCHEMA, outputSchema = QUOTE_OUTPUT_SCHEMA, description = "查询当前股票行情；与K线和技术指标共用同一数据源。")
    public String marketQuote(Map<String, Object> input, String rawInput) {
        String symbol = symbol(input, rawInput);
        if (symbol.isBlank()) return fail("market_quote", "symbol 不能为空", false);
        try {
            return success("market_quote", analysisService.marketQuote(symbol, asString(input.get("end_date")), firstNonBlank(asString(input.get("adjustment")), "FORWARD")), "[\"symbol 非空\",\"行情与K线使用同一数据提供方\"]");
        } catch (IllegalArgumentException e) {
            return fail("market_quote", e.getMessage(), false);
        } catch (Exception e) {
            return fail("market_quote", "行情查询失败: " + e.getMessage(), true);
        }
    }

    @AgentTool(name = "historical_bars", title = "历史K线查询", namespace = "finance.market", category = "market_data", tags = {"stock", "ohlcv", "readonly"}, inputSchema = BARS_SCHEMA, description = "返回指定股票日线OHLCV；仅在需要K线、图表或原始数据时调用。")
    public String historicalBars(Map<String, Object> input, String rawInput) {
        String symbol = symbol(input, rawInput);
        if (symbol.isBlank()) return fail("historical_bars", "symbol 不能为空", false);
        try {
            return success("historical_bars", analysisService.historicalBars(symbol, asString(input.get("start_date")), asString(input.get("end_date")), firstNonBlank(asString(input.get("adjustment")), "FORWARD")), "[\"symbol 非空\",\"OHLCV 数据已标准化\"]");
        } catch (IllegalArgumentException e) {
            return fail("historical_bars", e.getMessage(), false);
        } catch (Exception e) {
            return fail("historical_bars", "K线查询失败: " + e.getMessage(), true);
        }
    }
}
