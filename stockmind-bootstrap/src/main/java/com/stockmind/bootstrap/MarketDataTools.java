package com.stockmind.bootstrap;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.analysis.TechnicalAnalysisService;
import com.stockmind.application.market.MarketDataNotFoundException;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Market facts and raw OHLCV retrieval tools.
 */
@Component
public class MarketDataTools extends StockToolSupport {
    private static final Logger LOG = LoggerFactory.getLogger(MarketDataTools.class);
    private static final String QUOTE_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$","description":"6位A股、指数或ETF代码"},"end_date":{"type":"string","format":"date"},"adjustment":{"type":"string","enum":["NONE","FORWARD","BACKWARD"]}},"required":["symbol"],"additionalProperties":false}
            """;
    private static final String QUOTE_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"tool":{"type":"string","enum":["market_quote"]},"status":{"type":"string","enum":["success","failed"]},"validation_passed":{"type":"boolean"},"validation_rules":{"type":"array","items":{"type":"string"}},"validation_errors":{"type":"array","items":{"type":"string"}},"retryable":{"type":"boolean"},"error_code":{"type":"string"},"data":{"type":"object","properties":{"symbol":{"type":"string"},"dataset_id":{"type":"string"},"source":{"type":"string"},"as_of":{"type":"string"},"start_at":{"type":"string"},"end_at":{"type":"string"},"interval":{"type":"string"},"adjustment":{"type":"string"},"warnings":{"type":"array"},"price":{"type":"number"},"daily_change_pct":{"type":"number","description":"最新交易日收盘价相对前一交易日收盘价的涨跌幅，单位为百分比；不是查询区间涨跌幅"},"volume":{"type":"number"}},"required":["symbol","source","as_of","price","daily_change_pct","volume"]},"metadata":{"type":"object"}},"required":["tool","status","validation_passed","validation_rules","validation_errors","retryable","error_code","data","metadata"],"additionalProperties":false}
            """;
    private static final String BARS_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$","description":"6位A股、指数或ETF代码"},"start_date":{"type":"string","description":"yyyy-MM-dd"},"end_date":{"type":"string","description":"yyyy-MM-dd"},"adjustment":{"type":"string","enum":["NONE","FORWARD","BACKWARD"]}},"required":["symbol"]}
            """;
    private final TechnicalAnalysisService analysisService;

    public MarketDataTools(TechnicalAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @AgentTool(name = "market_quote", title = "股票行情查询", namespace = "finance.market", category = "market_data", tags = {"stock", "quote", "readonly"}, inputSchema = QUOTE_SCHEMA, outputSchema = QUOTE_OUTPUT_SCHEMA, description = "查询当前股票行情；daily_change_pct 表示最新交易日的单日涨跌幅，不表示查询区间涨跌幅；与K线和技术指标共用同一数据源。")
    public String marketQuote(Map<String, Object> input, String rawInput) {
        String symbol = symbol(input, rawInput);
        if (symbol.isBlank()) return fail("market_quote", StockToolError.SYMBOL_REQUIRED);
        try {
            return success("market_quote", analysisService.marketQuote(symbol, asString(input.get("end_date")), firstNonBlank(asString(input.get("adjustment")), "FORWARD")), "[\"symbol 非空\",\"行情与K线使用同一数据提供方\"]");
        } catch (MarketDataNotFoundException e) {
            return fail("market_quote", StockToolError.MARKET_DATA_NOT_FOUND);
        } catch (IllegalArgumentException e) {
            return fail("market_quote", StockToolError.INVALID_MARKET_QUERY);
        } catch (Exception e) {
            logInternalFailure("market_quote", e);
            return fail("market_quote", StockToolError.MARKET_DATA_UNAVAILABLE);
        }
    }

    @AgentTool(name = "historical_bars", title = "历史K线查询", namespace = "finance.market", category = "market_data", tags = {"stock", "ohlcv", "readonly"}, inputSchema = BARS_SCHEMA, description = "返回指定股票日线OHLCV；仅在需要K线、图表或原始数据时调用。")
    public String historicalBars(Map<String, Object> input, String rawInput) {
        String symbol = symbol(input, rawInput);
        if (symbol.isBlank()) return fail("historical_bars", StockToolError.SYMBOL_REQUIRED);
        try {
            return success("historical_bars", analysisService.historicalBars(symbol, asString(input.get("start_date")), asString(input.get("end_date")), firstNonBlank(asString(input.get("adjustment")), "FORWARD")), "[\"symbol 非空\",\"OHLCV 数据已标准化\"]");
        } catch (MarketDataNotFoundException e) {
            return fail("historical_bars", StockToolError.MARKET_DATA_NOT_FOUND);
        } catch (IllegalArgumentException e) {
            return fail("historical_bars", StockToolError.INVALID_MARKET_QUERY);
        } catch (Exception e) {
            logInternalFailure("historical_bars", e);
            return fail("historical_bars", StockToolError.MARKET_DATA_UNAVAILABLE);
        }
    }

    private void logInternalFailure(String tool, Exception error) {
        LOG.warn("Business tool dependency failed, tool={}, exceptionType={}", tool, error.getClass().getName());
        LOG.debug("Business tool dependency failure details, tool={}", tool, error);
    }
}
