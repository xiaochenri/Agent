package com.stockmind.bootstrap;

import com.agent.javascope.spi.AgentTool;
import com.stockmind.common.vector.DistanceMetric;
import com.stockmind.common.vector.OceanBaseVectorStore;
import com.stockmind.common.vector.TextVectorBuilder;
import com.stockmind.common.vector.VectorSearchResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StockAgentTools {

    private final OceanBaseVectorStore vectorStore;
    private final int vectorDimensions;
    private final int knowledgeDefaultTopK;

    public StockAgentTools(
            DataSource dataSource,
            @Value("${stockmind.knowledge.vector.table:stock_doc_vector}") String vectorTableName,
            @Value("${stockmind.knowledge.vector.dimensions:1024}") int vectorDimensions,
            @Value("${stockmind.knowledge.vector.default-top-k:5}") int knowledgeDefaultTopK) {
        this.vectorStore = new OceanBaseVectorStore(dataSource, vectorTableName, vectorDimensions);
        this.vectorDimensions = vectorDimensions;
        this.knowledgeDefaultTopK = Math.max(1, knowledgeDefaultTopK);
    }

    @AgentTool(
            name = "market_quote",
            description = "用途：查询指定股票实时行情。"
                    + " 仅当已明确 symbol 时使用；禁止用于需求澄清。"
                    + " 输入要求：必须提供 symbol（股票代码）。"
                    + " 输出：symbol、price、change_pct、volume、as_of。")
    public String marketQuote(Map<String, Object> input, String rawInput) {
        String symbol = firstNonBlank((String) input.get("symbol"), extractSymbolFromInput(rawInput));
        if (symbol.isBlank()) {
            return fail("market_quote", "symbol 不能为空", true);
        }
        String normalizedSymbol = symbol.trim().toUpperCase();
        int seed = Math.abs((normalizedSymbol + LocalDate.now()).hashCode());
        double basePrice = 20 + (seed % 70000) / 100.0; // 20.00 ~ 720.00
        double changePct = ((seed / 17) % 1201) / 100.0 - 6.0; // -6.00% ~ +6.00%
        double price = basePrice * (1 + changePct / 100.0);
        long volume = 500_000L + (seed % 25_000_000L); // 50万 ~ 2550万

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("symbol", normalizedSymbol);
        quote.put("price", round2(price));
        quote.put("change_pct", round2(changePct));
        quote.put("volume", volume);
        quote.put("source", "mock_market_feed");
        quote.put("as_of", LocalDate.now().toString());
        return success("market_quote", toJson(quote), "[\"symbol 非空\",\"price 为数值\",\"change_pct 为数值\",\"volume 为整数\",\"source 存在\"]");
    }

    @AgentTool(
            name = "news_search",
            description = "用途：按关键词检索相关新闻。"
                    + " 仅当已明确关键词/主题（可含时间范围）时使用；禁止用于需求澄清。"
                    + " 输入要求：必须提供 keyword。"
                    + " 输出：相关新闻条目列表。")
    public String newsSearch(Map<String, Object> input, String rawInput) {
        String keyword = firstNonBlank((String) input.get("keyword"), rawInput);
        if (keyword.isBlank()) {
            return fail("news_search", "keyword 不能为空", true);
        }
        String data = "{\"keyword\":\"" + safe(keyword) + "\",\"items\":[\"mock-news-1\",\"mock-news-2\"]}";
        return fail("news_search", "无法检索相关新闻", false);
    }

    @AgentTool(
            name = "knowledge_search",
            description = "用途：从财报/年报/季报知识库检索证据片段。"
                    + " 仅当已明确 query（建议包含 symbol 或公司名）时使用；禁止用于需求澄清。"
                    + " 输入要求：必须提供 query，可选 symbol、top_k。"
                    + " 输出：按相关性排序的证据片段（含 score、distance、metadata）。")
    public String knowledgeSearch(Map<String, Object> input, String rawInput) {
        String symbol = firstNonBlank((String) input.get("symbol"), "");
        String query = firstNonBlank((String) input.get("query"), (String) input.get("keyword"), rawInput);
        if (query.isBlank()) {
            return fail("knowledge_search", "query 不能为空", true);
        }
        int topK = parseTopK(input.get("top_k"), knowledgeDefaultTopK);
        String retrievalText = symbol.isBlank() ? query : symbol + " " + query;
        try {
            vectorStore.createTableIfNotExists();
            float[] queryVector = TextVectorBuilder.build(retrievalText, vectorDimensions);
            List<VectorSearchResult> results = vectorStore.search(queryVector, topK, DistanceMetric.COSINE);
            List<Map<String, Object>> items = new ArrayList<>();
            for (VectorSearchResult result : results) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", result.id());
                item.put("score", result.score());
                item.put("distance", result.distance());
                item.put("content", truncate(result.content(), 500));
                item.put("metadata", result.metadataJson());
                items.add(item);
            }
            String data = "{\"symbol\":\"" + safe(symbol) + "\",\"query\":\"" + safe(query)
                    + "\",\"top_k\":" + topK + ",\"items\":" + toJson(items) + "}";
            return success("knowledge_search", data, "[\"query 非空\",\"向量检索完成\",\"items 为数组\"]");
        } catch (Exception e) {
            return fail("knowledge_search", "知识库检索失败: " + e.getMessage(), true);
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private String firstNonBlank(String first, String second, String third) {
        String value = firstNonBlank(first, "");
        if (!value.isBlank()) {
            return value;
        }
        value = firstNonBlank(second, "");
        if (!value.isBlank()) {
            return value;
        }
        return firstNonBlank(third, "");
    }

    private int parseTopK(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(1, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String extractSymbolFromInput(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher aShareMatcher = Pattern.compile("\\b\\d{6}\\b").matcher(text);
        if (aShareMatcher.find()) {
            return aShareMatcher.group();
        }
        Matcher usTickerMatcher = Pattern.compile("\\$?[A-Z]{1,5}\\b").matcher(text);
        if (usTickerMatcher.find()) {
            return usTickerMatcher.group().replace("$", "");
        }
        return "";
    }

    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLen);
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "[]";
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String success(String toolName, String data, String rules) {
        return "{\"tool\":\"" + safe(toolName) + "\",\"status\":\"success\",\"validation_passed\":true,"
                + "\"validation_rules\":" + rules + ",\"validation_errors\":[],\"retryable\":false,\"data\":" + data + "}";
    }

    private String fail(String toolName, String error, boolean retryable) {
        return "{\"tool\":\"" + safe(toolName) + "\",\"status\":\"failed\",\"validation_passed\":false,"
                + "\"validation_rules\":[],\"validation_errors\":[\"" + safe(error) + "\"],\"retryable\":" + retryable + ",\"data\":null}";
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
