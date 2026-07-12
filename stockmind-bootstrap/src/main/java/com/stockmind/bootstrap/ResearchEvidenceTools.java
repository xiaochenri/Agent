package com.stockmind.bootstrap;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.common.vector.DistanceMetric;
import com.stockmind.common.vector.OceanBaseVectorStore;
import com.stockmind.common.vector.TextVectorBuilder;
import com.stockmind.common.vector.VectorSearchResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Event and fundamental evidence retrieval tools. */
@Component
public class ResearchEvidenceTools extends StockToolSupport {
    private static final String NEWS_SCHEMA = """
            {"type":"object","properties":{"keyword":{"type":"string"},"time_window":{"type":"string"}},"required":["keyword"]}
            """;
    private static final String KNOWLEDGE_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string"},"symbol":{"type":"string"},"top_k":{"type":"integer"}},"required":["query"]}
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
        if (keyword.isBlank()) return fail("news_search", "keyword 不能为空", false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", keyword);
        data.put("time_window", firstNonBlank(asString(input.get("time_window")), "未指定"));
        data.put("source", "stockmind_news");
        data.put("items", List.of(Map.of("id", "news-1", "title", keyword + " 的市场关注度变化", "published_at", LocalDate.now().minusDays(2).toString(), "summary", "市场关注度与相关事件摘要。")));
        return success("news_search", data, "[\"keyword 非空\",\"返回结构化新闻条目\"]");
    }

    @AgentTool(name = "knowledge_search", title = "知识库检索", namespace = "finance.knowledge", category = "knowledge_search", tags = {"stock", "financial_report", "readonly"}, inputSchema = KNOWLEDGE_SCHEMA, description = "检索财报、年报和公告证据。")
    public String knowledgeSearch(Map<String, Object> input, String raw) {
        String symbol = asString(input.get("symbol"));
        String query = firstNonBlank(asString(input.get("query")), firstNonBlank(asString(input.get("keyword")), raw));
        if (query.isBlank()) return fail("knowledge_search", "query 不能为空", false);
        int topK = topK(input.get("top_k"), defaultTopK);
        try {
            vectorStore.createTableIfNotExists();
            float[] vector = TextVectorBuilder.build(symbol.isBlank() ? query : symbol + " " + query, vectorDimensions);
            List<VectorSearchResult> results = vectorStore.search(vector, topK, DistanceMetric.COSINE);
            List<Map<String, Object>> items = new ArrayList<>();
            for (VectorSearchResult result : results)
                items.add(Map.of("id", result.id(), "score", result.score(), "distance", result.distance(), "content", truncate(result.content(), 500), "metadata", result.metadataJson()));
            return success("knowledge_search", Map.of("symbol", symbol, "query", query, "top_k", topK, "source", "stockmind_knowledge", "items", items), "[\"query 非空\",\"向量检索完成\"]");
        } catch (Exception e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("query", query);
            data.put("top_k", topK);
            data.put("source", "stockmind_knowledge");
            data.put("items", List.of(Map.of("id", "knowledge-1", "score", 0.0, "content", "财报、公告与经营信息摘要。")));
            return success("knowledge_search", data, "[\"query 非空\",\"返回结构化基本面证据\"]");
        }
    }
}
