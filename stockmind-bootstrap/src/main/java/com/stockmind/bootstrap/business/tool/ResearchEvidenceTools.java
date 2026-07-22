package com.stockmind.bootstrap.business.tool;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.announcement.AnnouncementProvider;
import com.stockmind.application.analysis.SupplementalEvidenceAnalysisService;
import com.stockmind.domain.analysis.BusinessSignal;
import com.stockmind.application.market.StockTimeWindowResolver;
import com.stockmind.application.news.NewsArticle;
import com.stockmind.application.news.NewsEvidenceSemantics;
import com.stockmind.application.news.NewsProvider;
import com.stockmind.application.financial.FinancialReportProvider;
import com.stockmind.application.financial.FinancialStatementPeriod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Event and fundamental evidence retrieval tools. */
@Component
public class ResearchEvidenceTools extends StockToolSupport {
    private static final Logger LOG = LoggerFactory.getLogger(ResearchEvidenceTools.class);
    private static final String NEWS_SCHEMA = """
            {"type":"object","properties":{"keyword":{"type":"string"},"time_window":{"type":"string"},"start_date":{"type":"string","description":"yyyy-MM-dd"},"end_date":{"type":"string","description":"yyyy-MM-dd"}},"required":["keyword"]}
            """;
    private static final String KNOWLEDGE_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string"},"symbol":{"type":"string","pattern":"^\\\\d{6}$"},"time_window":{"type":"string"},"start_date":{"type":"string","format":"date"},"end_date":{"type":"string","format":"date"},"top_k":{"type":"integer","minimum":1,"maximum":50}},"required":["query"],"additionalProperties":false}
            """;
    private static final String FINANCIAL_REPORT_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$"},"report_period":{"type":"string","minLength":1,"description":"明确且可归一化的财报期间，如2024年Q1或2024-03-31"},"top_k":{"type":"integer","minimum":1}},"required":["symbol","report_period"],"additionalProperties":false}
            """;
    private static final String FINANCIAL_REPORT_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"tool":{"type":"string","enum":["financial_report_metrics"]},"status":{"type":"string","enum":["success","failed"]},"validation_passed":{"type":"boolean"},"validation_rules":{"type":"array","items":{"type":"string"}},"validation_errors":{"type":"array","items":{"type":"string"}},"retryable":{"type":"boolean"},"error_code":{"type":"string"},"data":{"type":"object","properties":{"symbol":{"type":"string"},"report_period":{"type":"string","format":"date"},"report_type":{"type":"string","enum":["Q1","H1","Q3","ANNUAL"]},"net_profit":{"type":["number","null"]},"total_shares":{"type":["number","null"]},"reported_basic_eps":{"type":["number","null"]},"currency":{"type":"string"},"source":{"type":"string"},"source_documents":{"type":"array","items":{"type":"object"}},"missing_fields":{"type":"array","items":{"type":"string"}},"calculation_ready":{"type":"boolean"},"data_quality":{"type":"string","enum":["valid","partial","invalid"]},"business_signals":{"type":"array","items":{"type":"object"}},"analysis_capability":{"type":"object"}},"required":["symbol","report_period","report_type","net_profit","total_shares","reported_basic_eps","currency","source","source_documents","missing_fields","calculation_ready","data_quality"],"additionalProperties":false},"metadata":{"type":"object"}},"required":["tool","status","validation_passed","validation_rules","validation_errors","retryable","error_code","data","metadata"],"additionalProperties":false}
            """;
    private static final String LATEST_FINANCIAL_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$"},"as_of":{"type":"string","format":"date"},"periods":{"type":"integer","minimum":1,"maximum":20}},"required":["symbol"],"additionalProperties":false}
            """;
    private final NewsProvider newsProvider;
    private final FinancialReportProvider financialReportProvider;
    private final AnnouncementProvider announcementProvider;
    private final SupplementalEvidenceAnalysisService analysis = new SupplementalEvidenceAnalysisService();

    public ResearchEvidenceTools(NewsProvider newsProvider,
                                 FinancialReportProvider financialReportProvider,
                                 AnnouncementProvider announcementProvider) {
        this.newsProvider = newsProvider;
        this.financialReportProvider = financialReportProvider;
        this.announcementProvider = announcementProvider;
    }

    @AgentTool(name = "news_search", title = "新闻检索", namespace = "finance.news", category = "news_search", tags = {"stock", "news", "readonly"}, inputSchema = NEWS_SCHEMA, description = "检索近期新闻和市场背景，返回证据类型以及可更新事件风险或市场预期论点的业务信号。")
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
        try {
            List<NewsArticle> articles = newsProvider.search(keyword, window.startDate(), window.endDate(), 30);
            List<Map<String, Object>> items = new ArrayList<>();
            for (NewsArticle article : articles) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", article.id());
                item.put("title", article.title());
                item.put("published_at", article.publishedAt().toString());
                item.put("summary", article.summary());
                item.put("source", article.source());
                item.put("url", article.url());
                item.put("evidence_semantics", NewsEvidenceSemantics.classify(article, keyword));
                items.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("keyword", keyword);
            data.put("time_window", window.display());
            data.put("start_date", window.startDate().toString());
            data.put("end_date", window.endDate().toString());
            data.put("warnings", window.warnings());
            data.put("source", "eastmoney_search_api");
            data.put("data_mode", "live_api");
            data.put("result_quality", items.isEmpty() ? "no_matches" : "valid");
            data.put("returned_count", items.size());
            data.put("items", items);
            data.put("business_signals", StockBusinessSignalMapper.maps(
                    analysis.newsSignals(articles, keyword, window.endDate())));
            data.put("analysis_capability", Map.of(
                    "capability", "EVENT_DISCOVERY",
                    "resolution_mode", "DISCOVERY_ONLY",
                    "updates_agenda_ids", List.of("recent_event_impact")));
            return success("news_search", data,
                    "[\"keyword 非空\",\"新闻来自真实公开接口\",\"结果按请求时间窗过滤\"]");
        } catch (Exception e) {
            logInternalFailure("news_search", e);
            return fail("news_search", StockToolError.NEWS_SERVICE_UNAVAILABLE);
        }
    }

    @AgentTool(name = "knowledge_search", title = "跨来源公开资料检索", namespace = "finance.knowledge", category = "knowledge_search", tags = {"stock", "news", "announcement", "financial_report", "readonly"}, inputSchema = KNOWLEDGE_SCHEMA, description = "当问题横跨新闻、公告和财报且来源尚不明确时做资料发现。已知需要行情、公告或结构化财务指标时，直接使用对应专用工具。")
    public String knowledgeSearch(Map<String, Object> input, String raw) {
        String symbol = asString(input.get("symbol"));
        String query = firstNonBlank(asString(input.get("query")), firstNonBlank(asString(input.get("keyword")), raw));
        if (query.isBlank()) return fail("knowledge_search", StockToolError.QUERY_REQUIRED);
        int topK = Math.min(50, positiveInt(input.get("top_k"), 10));
        try {
            var window = StockTimeWindowResolver.resolveNews(asString(input.get("start_date")),
                    asString(input.get("end_date")), asString(input.get("time_window")));
            List<Map<String, Object>> newsItems = new ArrayList<>();
            List<NewsArticle> discoveredArticles = new ArrayList<>();
            List<Map<String, Object>> announcementItems = new ArrayList<>();
            List<Map<String, Object>> financialItems = new ArrayList<>();
            List<String> sourceErrors = new ArrayList<>();
            int successfulSources = 0;
            try {
                List<NewsArticle> articles = newsProvider.search(query, window.startDate(), window.endDate(), topK);
                discoveredArticles.addAll(articles);
                successfulSources++;
                for (NewsArticle article : articles) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "news");
                    item.put("title", article.title());
                    item.put("published_at", article.publishedAt().toString());
                    item.put("summary", article.summary());
                    item.put("source", article.source());
                    item.put("url", article.url());
                    item.put("evidence_semantics", NewsEvidenceSemantics.classify(article, query));
                    newsItems.add(item);
                }
            } catch (Exception e) {
                sourceErrors.add("eastmoney_news_unavailable");
            }
            if (!symbol.isBlank()) {
                try {
                    var announcements = announcementProvider.search(symbol, window.startDate(), window.endDate(), topK);
                    successfulSources++;
                    announcements.forEach(a -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("type", "announcement");
                        item.put("title", a.title());
                        item.put("published_at", a.publishedDate().toString());
                        item.put("source", "cninfo_official_api");
                        item.put("url", a.detailUrl());
                        item.put("pdf_url", a.pdfUrl());
                        item.put("evidence_semantics", NewsEvidenceSemantics.officialAnnouncementMetadata());
                        announcementItems.add(item);
                    });
                } catch (Exception e) {
                    sourceErrors.add("cninfo_announcement_unavailable");
                }
                for (String statement : List.of("lrb", "fzb", "llb")) {
                    try {
                        FinancialStatementPeriod report = latestAtOrBefore(
                                financialReportProvider.load(symbol, statement, 8), window.endDate());
                        successfulSources++;
                        if (report != null) financialItems.add(financialKnowledgeItem(report));
                    } catch (Exception e) {
                        sourceErrors.add("sina_" + statement + "_unavailable");
                    }
                }
            }
            if (successfulSources == 0) return fail("knowledge_search", StockToolError.KNOWLEDGE_STORE_UNAVAILABLE);
            List<Map<String, Object>> returnedItems = mergeEvidence(
                    announcementItems, financialItems, newsItems, topK);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("query", query);
            data.put("top_k", topK);
            data.put("start_date", window.startDate().toString());
            data.put("end_date", window.endDate().toString());
            data.put("window_warnings", window.warnings());
            data.put("source", List.of("eastmoney_search_api", "cninfo_official_api", "sina_financial_api"));
            data.put("data_mode", "live_public_api");
            data.put("source_result_counts", Map.of(
                    "news", newsItems.size(),
                    "announcements", announcementItems.size(),
                    "financial_statements", financialItems.size()));
            data.put("items", returnedItems);
            List<BusinessSignal> businessSignals = new ArrayList<>(
                    analysis.newsSignals(discoveredArticles, query, window.endDate()));
            if (!announcementItems.isEmpty()) {
                businessSignals.add(analysis.announcementMetadataSignal(
                        announcementItems.size(), window.startDate(), window.endDate()));
            }
            data.put("business_signals", StockBusinessSignalMapper.maps(businessSignals));
            data.put("analysis_capability", Map.of(
                    "updated_theses", List.of("EVENT_RISK", "EXPECTATIONS"),
                    "resolution_mode", "DISCOVERY_ONLY"));
            data.put("source_errors", sourceErrors);
            data.put("data_quality", returnedItems.isEmpty() ? "no_matches" : sourceErrors.isEmpty() ? "valid" : "partial");
            return success("knowledge_search", data,
                    "[\"query 非空\",\"结果来自公开HTTP接口\",\"未访问向量库或本地文档库\"]");
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
        try {
            FinancialStatementPeriod income = findPeriod(financialReportProvider.load(symbol, "lrb", 20), reportPeriod);
            FinancialStatementPeriod balance = findPeriod(financialReportProvider.load(symbol, "fzb", 20), reportPeriod);
            if (income == null && balance == null) {
                return fail("financial_report_metrics", StockToolError.REPORT_NOT_FOUND);
            }
            Double netProfit = metric(income, "归属于母公司所有者的净利润", "归属于母公司股东的净利润",
                    "归属于上市公司股东的净利润", "归母净利润", "净利润");
            Double totalShares = metric(balance, "实收资本（或股本）", "实收资本(或股本)", "股本", "期末股本");
            Double reportedEps = metric(income, "基本每股收益", "基本EPS", "EPS");
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
            data.put("source", "sina_financial_api");
            data.put("source_documents", List.of(Map.of("statement", "income", "period", reportPeriod),
                    Map.of("statement", "balance", "period", reportPeriod)));
            data.put("missing_fields", missingFields);
            data.put("calculation_ready", ready);
            data.put("data_quality", ready ? "valid" : income == null && balance == null ? "invalid" : "partial");
            data.put("business_signals", List.of());
            data.put("analysis_capability", Map.of(
                    "available_facts", List.of("net_profit", "total_shares", "reported_basic_eps"),
                    "resolves_issue_ids", List.of(),
                    "note", "用于指定报告期指标读取，不提供扣非利润或盈利归因信号"));
            return success("financial_report_metrics", data,
                    "[\"symbol和report_period非空\",\"财报指标来自新浪结构化接口\",\"输出计算就绪状态\"]");
        } catch (Exception e) {
            logInternalFailure("financial_report_metrics", e);
            return fail("financial_report_metrics", StockToolError.FINANCIAL_REPORT_SERVICE_UNAVAILABLE);
        }
    }

    @AgentTool(name = "latest_financial_report", title = "最新已披露结构化财报",
            namespace = "finance.fundamental", category = "financial_report",
            tags = {"stock", "financial_report", "latest", "readonly"}, inputSchema = LATEST_FINANCIAL_SCHEMA,
            description = "读取截至as_of最新已披露的利润表、资产负债表和现金流量表摘要。适合最新基本面事实；多期趋势使用financial_trend_analysis，综合投资价值使用stock_factor_profile。")
    public String latestFinancialReport(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("latest_financial_report", StockToolError.SYMBOL_REQUIRED);
        int periods = Math.min(20, positiveInt(input.get("periods"), 8));
        java.time.LocalDate asOf;
        try {
            asOf = asString(input.get("as_of")).isBlank() ? java.time.LocalDate.now()
                    : java.time.LocalDate.parse(asString(input.get("as_of")));
        } catch (Exception e) {
            return fail("latest_financial_report", StockToolError.INVALID_REPORT_PERIOD);
        }
        try {
            List<FinancialStatementPeriod> incomeReports = financialReportProvider.load(symbol, "lrb", periods);
            List<FinancialStatementPeriod> balanceReports = financialReportProvider.load(symbol, "fzb", periods);
            List<FinancialStatementPeriod> cashReports = financialReportProvider.load(symbol, "llb", periods);
            FinancialStatementPeriod income = latestAtOrBefore(incomeReports, asOf);
            FinancialStatementPeriod balance = latestAtOrBefore(balanceReports, asOf);
            FinancialStatementPeriod cash = latestAtOrBefore(cashReports, asOf);
            FinancialStatementPeriod primary = income != null ? income : balance != null ? balance : cash;
            if (primary == null) return fail("latest_financial_report", StockToolError.REPORT_NOT_FOUND);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("as_of", asOf.toString());
            data.put("report_period", primary.reportPeriod().toString());
            data.put("published_date", primary.publishedDate().toString());
            data.put("report_type", reportType(primary.reportPeriod()));
            data.put("source", "sina_financial_api");
            data.put("currency", "CNY");
            data.put("revenue", metric(income, "营业总收入", "营业收入"));
            data.put("net_profit", metric(income, "归属于母公司所有者的净利润", "归属于母公司股东的净利润",
                    "归属于上市公司股东的净利润", "净利润"));
            data.put("reported_basic_eps", metric(income, "基本每股收益"));
            data.put("total_assets", metric(balance, "资产总计", "资产合计"));
            data.put("total_liabilities", metric(balance, "负债合计"));
            data.put("total_shares", metric(balance, "实收资本（或股本）", "实收资本(或股本)", "股本"));
            data.put("operating_cash_flow", metric(cash, "经营活动产生的现金流量净额"));
            data.put("income_statement", selectedValues(income, List.of("营业总收入", "营业收入", "营业利润", "利润总额",
                    "归属于母公司所有者的净利润", "归属于上市公司股东的净利润", "基本每股收益")));
            data.put("balance_sheet", selectedValues(balance, List.of("资产总计", "负债合计", "货币资金",
                    "实收资本（或股本）", "股本")));
            data.put("cash_flow_statement", selectedValues(cash, List.of("经营活动产生的现金流量净额",
                    "投资活动产生的现金流量净额", "筹资活动产生的现金流量净额")));
            data.put("data_quality", "valid");
            data.put("business_signals", List.of());
            data.put("analysis_capability", Map.of(
                    "available_facts", List.of("latest_income", "latest_balance", "latest_cash_flow"),
                    "resolves_issue_ids", List.of(),
                    "note", "用于最新报告期事实读取，不提供扣非利润或盈利归因信号"));
            return success("latest_financial_report", data,
                    "[\"自动选择截至as_of的最新报告期\",\"三张财务报表来自结构化接口\",\"不依赖向量正则抽取\"]");
        } catch (Exception e) {
            logInternalFailure("latest_financial_report", e);
            return fail("latest_financial_report", StockToolError.FINANCIAL_REPORT_SERVICE_UNAVAILABLE);
        }
    }

    private void logInternalFailure(String tool, Exception error) {
        LOG.warn("Business tool dependency failed, tool={}, exceptionType={}", tool, error.getClass().getName());
        LOG.debug("Business tool dependency failure details, tool={}", tool, error);
    }

    /** Reserves space for official filings and structured statements before filling with news. */
    private List<Map<String, Object>> mergeEvidence(
            List<Map<String, Object>> announcements,
            List<Map<String, Object>> financials,
            List<Map<String, Object>> news,
            int topK) {
        List<Map<String, Object>> result = new ArrayList<>();
        append(result, announcements, Math.max(1, topK / 3), topK);
        append(result, financials, Math.max(1, topK / 3), topK);
        append(result, news, topK, topK);
        append(result, announcements, topK, topK);
        append(result, financials, topK, topK);
        return List.copyOf(result);
    }

    private void append(
            List<Map<String, Object>> target,
            List<Map<String, Object>> source,
            int sourceLimit,
            int totalLimit) {
        for (int index = 0; index < source.size()
                && index < sourceLimit && target.size() < totalLimit; index++) {
            Map<String, Object> item = source.get(index);
            if (!target.contains(item)) target.add(item);
        }
    }

    private FinancialStatementPeriod findPeriod(List<FinancialStatementPeriod> reports, String reportPeriod) {
        return reports.stream().filter(r -> r.reportPeriod().toString().equals(reportPeriod)).findFirst().orElse(null);
    }

    private FinancialStatementPeriod latestAtOrBefore(List<FinancialStatementPeriod> reports, java.time.LocalDate asOf) {
        return reports.stream().filter(r -> !r.publishedDate().isAfter(asOf))
                .max(java.util.Comparator.comparing(FinancialStatementPeriod::reportPeriod)).orElse(null);
    }

    private Map<String, Object> financialKnowledgeItem(FinancialStatementPeriod report) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "financial_statement");
        item.put("statement", report.statementType());
        item.put("report_period", report.reportPeriod().toString());
        item.put("published_at", report.publishedDate().toString());
        item.put("source", "sina_financial_api");
        item.put("metrics", report.values().entrySet().stream().limit(20)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new)));
        return item;
    }

    private Double metric(FinancialStatementPeriod report, String... labels) {
        if (report == null) return null;
        for (String label : labels) {
            String value = report.values().get(label);
            if (value == null) continue;
            try {
                String cleaned = value.replace(",", "").replace("元", "").replace("股", "").trim();
                double multiplier = 1;
                if (cleaned.endsWith("亿")) { multiplier = 100_000_000D; cleaned = cleaned.substring(0, cleaned.length() - 1); }
                else if (cleaned.endsWith("万")) { multiplier = 10_000D; cleaned = cleaned.substring(0, cleaned.length() - 1); }
                return Double.parseDouble(cleaned) * multiplier;
            } catch (NumberFormatException ignored) {
                // Continue with the next upstream label variant.
            }
        }
        return null;
    }

    private Map<String, Object> selectedValues(FinancialStatementPeriod report, List<String> labels) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (report == null) return values;
        values.put("report_period", report.reportPeriod().toString());
        values.put("published_date", report.publishedDate().toString());
        for (String label : labels) {
            if (report.values().containsKey(label)) values.put(label, report.values().get(label));
            if (report.yearOverYearValues().containsKey(label)) values.put(label + "_同比", report.yearOverYearValues().get(label));
        }
        return values;
    }

    private String reportType(java.time.LocalDate period) {
        return switch (period.getMonthValue()) {
            case 3 -> "Q1";
            case 6 -> "H1";
            case 9 -> "Q3";
            default -> "ANNUAL";
        };
    }
}
