package com.stockmind.bootstrap;

import com.agent.javascope.spi.AgentTool;
import com.agent.javascope.spi.ToolDangerLevel;
import com.agent.javascope.spi.ToolType;
import com.agent.javascope.spi.ToolVisibility;
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
            title = "股票行情查询",
            description = "用途：查询指定股票实时行情。"
                    + " 仅当已明确 symbol 时使用；禁止用于需求澄清。"
                    + " 输入要求：必须提供 symbol（股票代码）。"
                    + " 输出：symbol、price、change_pct、volume、as_of。",
            namespace = "finance.market",
            category = "market_data",
            tags = {"stock", "quote", "readonly"},
            toolType = ToolType.BUSINESS,
            visibility = ToolVisibility.MODEL_VISIBLE,
            dangerLevel = ToolDangerLevel.SAFE,
            readOnly = true,
            idempotent = true,
            requiresConfirmation = false,
            allowedDirectCall = true,
            allowedInPlanStep = true,
            timeoutMs = 5000,
            inputSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "symbol": {"type": "string", "description": "股票代码，例如 AAPL、TSLA、600519。"}
                      },
                      "required": ["symbol"]
                    }
                    """,
            outputSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "tool": {"type": "string"},
                        "status": {"type": "string", "enum": ["success", "failed"]},
                        "validation_passed": {"type": "boolean"},
                        "validation_rules": {"type": "array", "items": {"type": "string"}},
                        "validation_errors": {"type": "array", "items": {"type": "string"}},
                        "retryable": {"type": "boolean"},
                        "data": {
                          "type": "object",
                          "properties": {
                            "symbol": {"type": "string"},
                            "price": {"type": "number"},
                            "change_pct": {"type": "number"},
                            "volume": {"type": "integer"},
                            "source": {"type": "string"},
                            "as_of": {"type": "string"}
                          },
                          "required": ["symbol", "price", "change_pct", "volume", "as_of"]
                        }
                      },
                      "required": ["tool", "status", "validation_passed", "data"]
                    }
                    """)
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
            name = "stock_snapshot_analysis",
            title = "股票快照总结分析",
            description = "用途：基于已获得的行情、新闻或知识库信息，生成简要总结、风险提示、数据局限和下一步建议。"
                    + " 仅当用户明确要求“分析、总结、解读、怎么看、走势判断、风险提示、整体表现、简报”等总结性输出时使用。"
                    + " 不要在用户仅查询具体字段时使用本工具，例如：最新股价、涨跌幅、成交量、市值、市盈率、历史价格、公司资料等。"
                    + " 调用前必须确认用户请求包含明确分析意图；若只是原始数据查询，应只使用对应事实工具。"
                    + " 本工具不拉取新数据，只汇总调用方提供的已知数据；必须说明数据来源和局限，禁止直接给出买入/卖出建议。",
            namespace = "finance.analysis",
            category = "stock_analysis",
            tags = {"stock", "analysis", "summary", "readonly"},
            toolType = ToolType.BUSINESS,
            visibility = ToolVisibility.MODEL_VISIBLE,
            dangerLevel = ToolDangerLevel.SAFE,
            readOnly = true,
            idempotent = true,
            requiresConfirmation = false,
            allowedDirectCall = true,
            allowedInPlanStep = true,
            timeoutMs = 5000,
            inputSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "symbol": {"type": "string", "description": "股票代码，例如 AAPL、TSLA、600519。"},
                        "name": {"type": "string", "description": "股票或公司名称，可选。"},
                        "price": {"type": "number", "description": "最新价格，可来自 market_quote。"},
                        "change_pct": {"type": "number", "description": "涨跌幅百分比，可来自 market_quote。"},
                        "volume": {"type": "integer", "description": "成交量，可来自 market_quote。"},
                        "as_of": {"type": "string", "description": "数据日期或时间。"},
                        "market_context": {"type": "string", "description": "可选的行情补充背景，例如历史走势、资金流或行业背景。"},
                        "news_context": {"type": "string", "description": "可选的新闻摘要或检索结果要点。"},
                        "knowledge_context": {"type": "string", "description": "可选的财报、公告或知识库证据要点。"}
                      },
                      "required": ["symbol"]
                    }
                    """,
            outputSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "tool": {"type": "string"},
                        "status": {"type": "string", "enum": ["success", "failed"]},
                        "validation_passed": {"type": "boolean"},
                        "validation_rules": {"type": "array", "items": {"type": "string"}},
                        "validation_errors": {"type": "array", "items": {"type": "string"}},
                        "retryable": {"type": "boolean"},
                        "data": {
                          "type": "object",
                          "properties": {
                            "symbol": {"type": "string"},
                            "name": {"type": "string"},
                            "as_of": {"type": "string"},
                            "summary": {"type": "string"},
                            "known_info": {"type": "array", "items": {"type": "string"}},
                            "attention_points": {"type": "array", "items": {"type": "string"}},
                            "known_limits": {"type": "array", "items": {"type": "string"}},
                            "next_steps": {"type": "array", "items": {"type": "string"}}
                          },
                          "required": ["symbol", "summary", "known_info", "attention_points", "known_limits", "next_steps"]
                        }
                      },
                      "required": ["tool", "status", "validation_passed", "data"]
                    }
                    """)
    public String stockSnapshotAnalysis(Map<String, Object> input, String rawInput) {
        String symbol = firstNonBlank((String) input.get("symbol"), extractSymbolFromInput(rawInput));
        if (symbol.isBlank()) {
            return fail("stock_snapshot_analysis", "symbol 不能为空", true);
        }
        String normalizedSymbol = symbol.trim().toUpperCase();
        String name = firstNonBlank((String) input.get("name"), "");
        String asOf = firstNonBlank((String) input.get("as_of"), LocalDate.now().toString());
        Double price = parseDouble(input.get("price"));
        Double changePct = parseDouble(input.get("change_pct"));
        Long volume = parseLong(input.get("volume"));
        String marketContext = firstNonBlank((String) input.get("market_context"), "");
        String newsContext = firstNonBlank((String) input.get("news_context"), "");
        String knowledgeContext = firstNonBlank((String) input.get("knowledge_context"), "");

        List<String> knownInfo = new ArrayList<>();
        knownInfo.add("分析对象：" + (name.isBlank() ? normalizedSymbol : name + "（" + normalizedSymbol + "）"));
        if (price != null) {
            knownInfo.add("最新价格：" + round2(price));
        }
        if (changePct != null) {
            knownInfo.add("涨跌幅：" + round2(changePct) + "%");
        }
        if (volume != null) {
            knownInfo.add("成交量：" + volume);
        }
        knownInfo.add("数据时间：" + asOf);
        if (!marketContext.isBlank()) {
            knownInfo.add("行情背景：" + truncate(marketContext, 180));
        }
        if (!newsContext.isBlank()) {
            knownInfo.add("新闻要点：" + truncate(newsContext, 180));
        }
        if (!knowledgeContext.isBlank()) {
            knownInfo.add("知识库要点：" + truncate(knowledgeContext, 180));
        }

        List<String> attentionPoints = new ArrayList<>();
        String summary = buildSnapshotSummary(name, normalizedSymbol, price, changePct, volume, asOf);
        if (changePct != null) {
            double absChange = Math.abs(changePct);
            if (absChange >= 5) {
                attentionPoints.add("单日波动较大，需关注短期情绪和事件驱动风险。");
            } else if (absChange >= 2) {
                attentionPoints.add("单日波动有一定幅度，建议结合近几日走势判断是否延续。");
            } else {
                attentionPoints.add("单日波动相对温和，单独行情数据不足以判断趋势变化。");
            }
            if (changePct < 0) {
                attentionPoints.add("股价当日下跌，需观察是否伴随成交量放大或负面信息。");
            } else if (changePct > 0) {
                attentionPoints.add("股价当日上涨，需观察上涨是否有成交量和基本面信息支撑。");
            }
        } else {
            attentionPoints.add("未提供涨跌幅，无法判断当日价格方向和波动强度。");
        }
        if (volume == null) {
            attentionPoints.add("未提供成交量，暂无法判断交易活跃度。");
        }

        List<String> knownLimits = new ArrayList<>();
        knownLimits.add("本工具只基于调用方提供的数据做归纳，不会主动检索或补全新数据。");
        if (marketContext.isBlank()) {
            knownLimits.add("缺少历史走势、均线、量能对比等行情背景，趋势判断有限。");
        }
        if (newsContext.isBlank()) {
            knownLimits.add("缺少新闻、公告或事件信息，无法解释价格波动原因。");
        }
        if (knowledgeContext.isBlank()) {
            knownLimits.add("缺少财报、经营指标或估值信息，无法形成基本面判断。");
        }

        List<String> nextSteps = new ArrayList<>();
        nextSteps.add("补充近5日、20日或更长周期走势，判断短期波动是否延续。");
        nextSteps.add("检索相关新闻、公告或财报证据，确认价格变化是否有事件或基本面支撑。");
        nextSteps.add("如需更完整结论，可结合行业表现、估值指标和成交量对比继续分析。");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", normalizedSymbol);
        data.put("name", name);
        data.put("as_of", asOf);
        data.put("summary", summary);
        data.put("known_info", knownInfo);
        data.put("attention_points", attentionPoints);
        data.put("known_limits", knownLimits);
        data.put("next_steps", nextSteps);
        return success("stock_snapshot_analysis", toJson(data),
                "[\"symbol 非空\",\"仅基于已提供数据总结\",\"包含 summary/known_info/attention_points/known_limits/next_steps\",\"不包含直接买入或卖出建议\"]");
    }

    @AgentTool(
            name = "news_search",
            title = "新闻检索",
            description = "用途：按关键词检索相关新闻。"
                    + " 仅当已明确关键词/主题（可含时间范围）时使用；禁止用于需求澄清。"
                    + " 输入要求：必须提供 keyword。"
                    + " 输出：相关新闻条目列表。",
            namespace = "finance.news",
            category = "news_search",
            tags = {"stock", "news", "readonly"},
            toolType = ToolType.BUSINESS,
            visibility = ToolVisibility.MODEL_VISIBLE,
            dangerLevel = ToolDangerLevel.SAFE,
            readOnly = true,
            idempotent = true,
            requiresConfirmation = false,
            allowedDirectCall = true,
            allowedInPlanStep = true,
            timeoutMs = 8000,
            inputSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "keyword": {"type": "string", "description": "新闻检索关键词，建议包含股票代码、公司名或主题。"},
                        "time_window": {"type": "string", "description": "时间范围，例如 今天、近一周、近一月。"}
                      },
                      "required": ["keyword"]
                    }
                    """,
            outputSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "tool": {"type": "string"},
                        "status": {"type": "string", "enum": ["success", "failed"]},
                        "validation_passed": {"type": "boolean"},
                        "validation_errors": {"type": "array", "items": {"type": "string"}},
                        "retryable": {"type": "boolean"},
                        "data": {"type": "object"}
                      },
                      "required": ["tool", "status", "validation_passed"]
                    }
                    """)
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
            title = "知识库检索",
            description = "用途：从财报/年报/季报知识库检索证据片段。"
                    + " 仅当已明确 query（建议包含 symbol 或公司名）时使用；禁止用于需求澄清。"
                    + " 输入要求：必须提供 query，可选 symbol、top_k。"
                    + " 输出：按相关性排序的证据片段（含 score、distance、metadata）。",
            namespace = "finance.knowledge",
            category = "knowledge_search",
            tags = {"stock", "financial_report", "vector_search", "readonly"},
            toolType = ToolType.BUSINESS,
            visibility = ToolVisibility.MODEL_VISIBLE,
            dangerLevel = ToolDangerLevel.SAFE,
            readOnly = true,
            idempotent = true,
            requiresConfirmation = false,
            allowedDirectCall = true,
            allowedInPlanStep = true,
            timeoutMs = 15000,
            inputSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "query": {"type": "string", "description": "知识库检索 query，建议包含股票代码、公司名、财报主题或时间范围。"},
                        "symbol": {"type": "string", "description": "股票代码，可选。"},
                        "top_k": {"description": "返回条数，可选，默认使用系统配置。"}
                      },
                      "required": ["query"]
                    }
                    """,
            outputSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "tool": {"type": "string"},
                        "status": {"type": "string", "enum": ["success", "failed"]},
                        "validation_passed": {"type": "boolean"},
                        "validation_errors": {"type": "array", "items": {"type": "string"}},
                        "retryable": {"type": "boolean"},
                        "data": {
                          "type": "object",
                          "properties": {
                            "symbol": {"type": "string"},
                            "query": {"type": "string"},
                            "top_k": {"type": "integer"},
                            "items": {"type": "array", "items": {"type": "object"}}
                          },
                          "required": ["query", "items"]
                        }
                      },
                      "required": ["tool", "status", "validation_passed", "data"]
                    }
                    """)
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

    private Double parseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String buildSnapshotSummary(
            String name,
            String symbol,
            Double price,
            Double changePct,
            Long volume,
            String asOf) {
        String target = name.isBlank() ? symbol : name + "（" + symbol + "）";
        StringBuilder summary = new StringBuilder("基于已提供数据，").append(target);
        summary.append("截至").append(asOf);
        if (price != null) {
            summary.append("最新价格为").append(round2(price));
        } else {
            summary.append("暂无最新价格");
        }
        if (changePct != null) {
            summary.append("，涨跌幅为").append(round2(changePct)).append("%");
            if (changePct < 0) {
                summary.append("，短期价格表现偏弱");
            } else if (changePct > 0) {
                summary.append("，短期价格表现偏强");
            } else {
                summary.append("，价格基本持平");
            }
        }
        if (volume != null) {
            summary.append("，成交量为").append(volume);
        }
        summary.append("。该结论仅反映当前输入数据，不构成投资建议。");
        return summary.toString();
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
