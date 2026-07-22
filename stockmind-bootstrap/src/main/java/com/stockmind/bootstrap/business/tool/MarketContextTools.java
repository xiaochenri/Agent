package com.stockmind.bootstrap.business.tool;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.analysis.TechnicalAnalysisService;
import com.stockmind.application.sector.SectorDataProvider;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Market and sector context tools used to separate stock-specific moves from common factors. */
@Component
public class MarketContextTools extends StockToolSupport {
    private static final Logger LOG = LoggerFactory.getLogger(MarketContextTools.class);
    private static final String BENCHMARK_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$"},"benchmark":{"type":"string","description":"默认SH000300，可传SH000001或SZ399006"},"start_date":{"type":"string","format":"date"},"end_date":{"type":"string","format":"date"},"adjustment":{"type":"string","enum":["NONE","FORWARD","BACKWARD"]}},"required":["symbol"],"additionalProperties":false}
            """;
    private static final String SECTOR_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$"}},"required":["symbol"],"additionalProperties":false}
            """;
    private final TechnicalAnalysisService analysisService;
    private final SectorDataProvider sectorDataProvider;

    public MarketContextTools(TechnicalAnalysisService analysisService, SectorDataProvider sectorDataProvider) {
        this.analysisService = analysisService;
        this.sectorDataProvider = sectorDataProvider;
    }

    @AgentTool(name = "benchmark_performance", title = "市场基准同期表现",
            namespace = "finance.market", category = "relative_performance",
            tags = {"stock", "benchmark", "relative_return", "readonly"}, inputSchema = BENCHMARK_SCHEMA,
            description = "计算个股与沪深300等市场基准的同期收益和超额收益，用于区分个股与大盘因素。")
    public String benchmarkPerformance(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("benchmark_performance", StockToolError.SYMBOL_REQUIRED);
        try {
            return success("benchmark_performance", analysisService.benchmarkPerformance(symbol,
                    firstNonBlank(asString(input.get("benchmark")), "SH000300"),
                    asString(input.get("start_date")), asString(input.get("end_date")),
                    firstNonBlank(asString(input.get("adjustment")), "FORWARD")),
                    "[\"个股与基准使用同一数据源和窗口\",\"超额收益由确定性公式计算\"]");
        } catch (IllegalArgumentException e) {
            return fail("benchmark_performance", StockToolError.INVALID_MARKET_QUERY);
        } catch (Exception e) {
            LOG.warn("Benchmark performance failed", e);
            return fail("benchmark_performance", StockToolError.MARKET_DATA_UNAVAILABLE);
        }
    }

    @AgentTool(name = "sector_performance", title = "所属行业当日表现",
            namespace = "finance.market", category = "sector_performance",
            tags = {"stock", "sector", "industry", "readonly"}, inputSchema = SECTOR_SCHEMA,
            description = "查询个股所属东财行业板块及各板块当日涨跌幅；仅反映当日板块表现，不代表历史窗口收益。")
    public String sectorPerformance(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("sector_performance", StockToolError.SYMBOL_REQUIRED);
        try {
            var sectors = sectorDataProvider.loadIndustrySectors(symbol);
            List<Map<String, Object>> items = new ArrayList<>();
            sectors.forEach(s -> items.add(Map.of("code", s.code(), "name", s.name(),
                    "daily_change_pct", s.dailyChangePct(), "leader", s.leader())));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("as_of", LocalDate.now().toString());
            data.put("source", "eastmoney_sector_api");
            data.put("period", "current_trading_day");
            data.put("items", items);
            data.put("limitations", List.of("板块涨跌幅为当前交易日口径，不能与多日个股收益直接比较。"));
            return success("sector_performance", data,
                    "[\"行业归属来自个股板块接口\",\"仅保留行业板块\",\"明确当日口径\"]");
        } catch (Exception e) {
            LOG.warn("Sector performance failed", e);
            return fail("sector_performance", StockToolError.SECTOR_SERVICE_UNAVAILABLE);
        }
    }
}
