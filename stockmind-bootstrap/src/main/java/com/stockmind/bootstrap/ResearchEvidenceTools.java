package com.stockmind.bootstrap;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.common.vector.DistanceMetric;
import com.stockmind.common.vector.OceanBaseVectorStore;
import com.stockmind.common.vector.TextVectorBuilder;
import com.stockmind.common.vector.VectorSearchResult;
import com.stockmind.application.market.StockTimeWindowResolver;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Event and fundamental evidence retrieval tools. */
@Component
public class ResearchEvidenceTools extends StockToolSupport {
    private static final Logger LOG = LoggerFactory.getLogger(ResearchEvidenceTools.class);
    private static final String NEWS_SCHEMA = """
            {"type":"object","properties":{"keyword":{"type":"string"},"time_window":{"type":"string"},"start_date":{"type":"string","description":"yyyy-MM-dd"},"end_date":{"type":"string","description":"yyyy-MM-dd"}},"required":["keyword"]}
            """;
    private static final String KNOWLEDGE_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string"},"symbol":{"type":"string"},"top_k":{"type":"integer"}},"required":["query"]}
            """;
    private static final String FINANCIAL_REPORT_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^(\\\\d{6}|[A-Z]{1,5})$"},"report_period":{"type":"string","minLength":1,"description":"明确且可归一化的财报期间，如2024年Q1或2024-03-31"},"top_k":{"type":"integer","minimum":1}},"required":["symbol","report_period"],"additionalProperties":false}
            """;
    private static final String FINANCIAL_REPORT_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"tool":{"type":"string","enum":["financial_report_metrics"]},"status":{"type":"string","enum":["success","failed"]},"validation_passed":{"type":"boolean"},"validation_rules":{"type":"array","items":{"type":"string"}},"validation_errors":{"type":"array","items":{"type":"string"}},"retryable":{"type":"boolean"},"error_code":{"type":"string"},"data":{"type":"object","properties":{"symbol":{"type":"string"},"report_period":{"type":"string","format":"date"},"report_type":{"type":"string","enum":["Q1","H1","Q3","ANNUAL"]},"net_profit":{"type":["number","null"]},"total_shares":{"type":["number","null"]},"reported_basic_eps":{"type":["number","null"]},"currency":{"type":"string"},"source":{"type":"string"},"source_documents":{"type":"array","items":{"type":"object"}},"missing_fields":{"type":"array","items":{"type":"string"}},"calculation_ready":{"type":"boolean"},"data_quality":{"type":"string","enum":["valid","partial","invalid"]}},"required":["symbol","report_period","report_type","net_profit","total_shares","reported_basic_eps","currency","source","source_documents","missing_fields","calculation_ready","data_quality"],"additionalProperties":false},"metadata":{"type":"object"}},"required":["tool","status","validation_passed","validation_rules","validation_errors","retryable","error_code","data","metadata"],"additionalProperties":false}
            """;
    private final OceanBaseVectorStore vectorStore;
    private final int vectorDimensions;
    private final int defaultTopK;

    public ResearchEvidenceTools(DataSource dataSource,
                                 @Value("${stockmind.knowledge.vector.table:stock_doc_vector}") String table,
                                 @Value("${stockmind.knowledge.vector.dimensions:1024}") int dimensions,
                                 @Value("${stockmind.knowledge.vector.default-top-k:5}") int defaultTopK) {
        this.vectorStore = new OceanBaseVectorStore(dataSource, table, dimensions);
        this.vectorDimensions = dimensions;
        this.defaultTopK = Math.max(1, defaultTopK);
    }

    @AgentTool(name = "news_search", title = "新闻检索", namespace = "finance.news", category = "news_search", tags = {"stock", "news", "readonly"}, inputSchema = NEWS_SCHEMA, description = "按关键词查询事件证据。")
    public String newsSearch(Map<String, Object> input, String raw) {
        String keyword = firstNonBlank(asString(input.get("keyword")), raw);
        if (keyword.isBlank()) return fail("news_search", StockToolError.KEYWORD_REQUIRED);
        StockTimeWindowResolver.ResolvedTimeWindow window;
        try {
            window = StockTimeWindowResolver.resolveNews(
                    asString(input.get("start_date")), asString(input.get("end_date")), asString(input.get("time_window")));
        } catch (IllegalArgumentException error) {
            return fail("news_search", StockToolError.INVALID_TIME_WINDOW);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", keyword);
        data.put("time_window", window.display());
        data.put("start_date", window.startDate().toString());
        data.put("end_date", window.endDate().toString());
        data.put("warnings", window.warnings());
        data.put("source", "stockmind_news");
        data.put("items", List.of(Map.of("id", "news-1", "title", keyword + " 的市场关注度变化", "published_at", LocalDate.now().minusDays(2).toString(), "summary", "市场关注度与相关事件摘要。")));
        return success("news_search", data, "[\"keyword 非空\",\"返回结构化新闻条目\"]");
    }

    @AgentTool(name = "knowledge_search", title = "知识库检索", namespace = "finance.knowledge", category = "knowledge_search", tags = {"stock", "financial_report", "readonly"}, inputSchema = KNOWLEDGE_SCHEMA, description = "检索财报、年报和公告证据。")
    public String knowledgeSearch(Map<String, Object> input, String raw) {
        String symbol = asString(input.get("symbol"));
        String query = firstNonBlank(asString(input.get("query")), firstNonBlank(asString(input.get("keyword")), raw));
        if (query.isBlank()) return fail("knowledge_search", StockToolError.QUERY_REQUIRED);
        int topK = topK(input.get("top_k"), defaultTopK);
        try {
            vectorStore.createTableIfNotExists();
            float[] vector = TextVectorBuilder.build(symbol.isBlank() ? query : symbol + " " + query, vectorDimensions);
            List<VectorSearchResult> results = vectorStore.search(vector, topK, DistanceMetric.COSINE);
            if (results.isEmpty()) {
                return fail("knowledge_search", StockToolError.KNOWLEDGE_NOT_FOUND);
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (VectorSearchResult result : results)
                items.add(Map.of("id", result.id(), "score", result.score(), "distance", result.distance(), "content", truncate(result.content(), 500), "metadata", result.metadataJson()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("query", query);
            data.put("top_k", topK);
            data.put("source", "stockmind_knowledge");
            data.put("items", items);
            data.put("data_quality", items.isEmpty() ? "invalid" : "valid");
            return success("knowledge_search", data, "[\"query 非空\",\"向量检索完成\"]");
        } catch (Exception e) {
            // 基础设施异常不能伪装成检索成功，否则计划会把占位摘要当成真实财报证据。
            logInternalFailure("knowledge_search", e);
            return fail("knowledge_search", StockToolError.KNOWLEDGE_STORE_UNAVAILABLE);
        }
    }

    @AgentTool(
            name = "financial_report_metrics",
            title = "结构化财报指标读取",
            namespace = "finance.fundamental",
            category = "financial_report",
            tags = {"stock", "financial_report", "metrics", "readonly"},
            inputSchema = FINANCIAL_REPORT_SCHEMA,
            outputSchema = FINANCIAL_REPORT_OUTPUT_SCHEMA,
            description = "按明确 report_period 检索财报并提取归母净利润、总股本和披露的基本EPS；返回 data_quality、missing_fields 和 calculation_ready。")
    public String financialReportMetrics(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        String rawReportPeriod = asString(input.get("report_period"));
        FinancialReportPeriodResolver.ResolvedPeriod resolvedPeriod =
                FinancialReportPeriodResolver.resolve(rawReportPeriod).orElse(null);
        String reportPeriod = resolvedPeriod == null ? "" : resolvedPeriod.reportPeriod();
        if (symbol.isBlank()) return fail("financial_report_metrics", StockToolError.SYMBOL_REQUIRED);
        if (reportPeriod.isBlank()) return fail("financial_report_metrics", StockToolError.INVALID_REPORT_PERIOD);
        int topK = topK(input.get("top_k"), defaultTopK);
        try {
            vectorStore.createTableIfNotExists();
            String query = symbol + " " + reportPeriod + " 归母净利润 总股本 基本每股收益";
            List<VectorSearchResult> results = vectorStore.search(
                    TextVectorBuilder.build(query, vectorDimensions), topK, DistanceMetric.COSINE);
            if (results.isEmpty()) {
                return fail("financial_report_metrics", StockToolError.REPORT_NOT_FOUND);
            }
            StringBuilder evidenceText = new StringBuilder();
            List<Map<String, Object>> sources = new ArrayList<>();
            for (VectorSearchResult result : results) {
                evidenceText.append(result.content()).append('\n');
                sources.add(Map.of(
                        "id", result.id(),
                        "score", result.score(),
                        "content", truncate(result.content(), 300),
                        "metadata", result.metadataJson()));
            }
            Double netProfit = extractAmount(evidenceText.toString(),
                    "(?:归属于上市公司股东的净利润|归母净利润|净利润)", false);
            Double totalShares = extractAmount(evidenceText.toString(),
                    "(?:加权平均股本|总股本|期末股本)", true);
            Double reportedEps = extractPlainNumber(evidenceText.toString(),
                    "(?:基本每股收益|基本EPS|EPS)");
            List<String> missingFields = new ArrayList<>();
            if (netProfit == null) missingFields.add("net_profit");
            if (totalShares == null) missingFields.add("total_shares");
            if (reportedEps == null) missingFields.add("reported_basic_eps");
            boolean ready = reportedEps != null || (netProfit != null && totalShares != null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("report_period", reportPeriod);
            data.put("report_type", resolvedPeriod.reportType());
            data.put("net_profit", netProfit);
            data.put("total_shares", totalShares);
            data.put("reported_basic_eps", reportedEps);
            data.put("currency", "CNY");
            data.put("source", "stockmind_knowledge");
            data.put("source_documents", sources);
            data.put("missing_fields", missingFields);
            data.put("calculation_ready", ready);
            data.put("data_quality", ready ? "valid" : sources.isEmpty() ? "invalid" : "partial");
            return success("financial_report_metrics", data,
                    "[\"symbol和report_period非空\",\"财报指标来自检索文档\",\"输出计算就绪状态\"]");
        } catch (Exception e) {
            logInternalFailure("financial_report_metrics", e);
            return fail("financial_report_metrics", StockToolError.FINANCIAL_REPORT_SERVICE_UNAVAILABLE);
        }
    }

    private void logInternalFailure(String tool, Exception error) {
        LOG.warn("Business tool dependency failed, tool={}, exceptionType={}", tool, error.getClass().getName());
        LOG.debug("Business tool dependency failure details, tool={}", tool, error);
    }

    /** 识别带元/万元/亿元或股/万股/亿股单位的财报数值，并统一换算为基础单位。 */
    private Double extractAmount(String text, String labelPattern, boolean shares) {
        Pattern pattern = Pattern.compile(labelPattern + "[^\\d-]{0,30}(-?[\\d,]+(?:\\.\\d+)?)\\s*(亿元|万元|元|亿股|万股|股)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        try {
            double value = Double.parseDouble(matcher.group(1).replace(",", ""));
            String unit = matcher.group(2);
            if ("亿元".equals(unit) || "亿股".equals(unit)) value *= 100_000_000D;
            if ("万元".equals(unit) || "万股".equals(unit)) value *= 10_000D;
            if (shares && !(unit.endsWith("股"))) return null;
            if (!shares && unit.endsWith("股")) return null;
            return value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double extractPlainNumber(String text, String labelPattern) {
        Matcher matcher = Pattern.compile(labelPattern + "[^\\d-]{0,20}(-?[\\d,]+(?:\\.\\d+)?)",
                Pattern.CASE_INSENSITIVE).matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        try {
            return Double.parseDouble(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
