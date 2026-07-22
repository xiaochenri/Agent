package com.stockmind.bootstrap.business.tool;

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
            {"type":"object","properties":{"tool":{"type":"string","enum":["market_quote"]},"status":{"type":"string","enum":["success","failed"]},"validation_passed":{"type":"boolean"},"validation_rules":{"type":"array","items":{"type":"string"}},"validation_errors":{"type":"array","items":{"type":"string"}},"retryable":{"type":"boolean"},"error_code":{"type":"string"},"data":{"type":"object","properties":{"symbol":{"type":"string"},"name":{"type":"string"},"dataset_id":{"type":"string"},"source":{"type":"string"},"as_of":{"type":"string"},"start_at":{"type":"string"},"end_at":{"type":"string"},"interval":{"type":"string"},"adjustment":{"type":"string"},"warnings":{"type":"array"},"price":{"type":"number"},"open":{"type":"number"},"high":{"type":"number"},"low":{"type":"number"},"previous_close":{"type":"number"},"daily_change_amount":{"type":"number"},"daily_change_pct":{"type":"number","description":"最新交易日收盘价相对前一交易日收盘价的涨跌幅，单位为百分比；不是查询区间涨跌幅"},"volume":{"type":"number"},"amount_wan":{"type":"number","description":"成交额，腾讯接口口径，单位万元"},"turnover_pct":{"type":"number"},"pe_ttm":{"type":"number"},"pb":{"type":"number"},"market_cap_yi":{"type":"number"},"float_market_cap_yi":{"type":"number"},"limit_up":{"type":"number"},"limit_down":{"type":"number"},"volume_ratio":{"type":"number"}},"required":["symbol","source","as_of","price","daily_change_pct","volume"]},"metadata":{"type":"object"}},"required":["tool","status","validation_passed","validation_rules","validation_errors","retryable","error_code","data","metadata"],"additionalProperties":false}
            """;
    private static final String BARS_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$","description":"6位A股、指数或ETF代码"},"start_date":{"type":"string","description":"yyyy-MM-dd"},"end_date":{"type":"string","description":"yyyy-MM-dd"},"adjustment":{"type":"string","enum":["NONE","FORWARD","BACKWARD"]}},"required":["symbol"]}
            """;
    private static final String MOVE_SCHEMA = BARS_SCHEMA;
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

    @AgentTool(name = "price_move_analysis", title = "价格区间与回撤分析",
            namespace = "finance.market", category = "market_analysis",
            tags = {"stock", "returns", "drawdown", "volume", "readonly"},
            inputSchema = MOVE_SCHEMA,
            description = "确认指定窗口的区间涨跌、最大回撤、反弹幅度、关键涨跌日和上涨/下跌日量能；不解释因果。")
    public String priceMoveAnalysis(Map<String, Object> input, String rawInput) {
        String symbol = symbol(input, rawInput);
        if (symbol.isBlank()) return fail("price_move_analysis", StockToolError.SYMBOL_REQUIRED);
        try {
            return success("price_move_analysis", analysisService.priceMoveAnalysis(symbol,
                    asString(input.get("start_date")), asString(input.get("end_date")),
                    firstNonBlank(asString(input.get("adjustment")), "FORWARD")),
                    "[\"已确认价格窗口\",\"收益与回撤由K线确定性计算\",\"量价不作为因果证据\"]");
        } catch (MarketDataNotFoundException e) {
            return fail("price_move_analysis", StockToolError.MARKET_DATA_NOT_FOUND);
        } catch (IllegalArgumentException e) {
            return fail("price_move_analysis", StockToolError.INVALID_MARKET_QUERY);
        } catch (Exception e) {
            logInternalFailure("price_move_analysis", e);
            return fail("price_move_analysis", StockToolError.MARKET_DATA_UNAVAILABLE);
        }
    }

    private void logInternalFailure(String tool, Exception error) {
        LOG.warn("Business tool dependency failed, tool={}, exceptionType={}", tool, error.getClass().getName());
        LOG.debug("Business tool dependency failure details, tool={}", tool, error);
    }
}
