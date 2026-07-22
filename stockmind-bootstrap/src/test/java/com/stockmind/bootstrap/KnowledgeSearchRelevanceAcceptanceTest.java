package com.stockmind.bootstrap;

import com.stockmind.bootstrap.business.tool.ResearchEvidenceTools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.application.announcement.CompanyAnnouncement;
import com.stockmind.application.news.NewsArticle;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Locks historical windows and source balancing for public evidence search. */
public final class KnowledgeSearchRelevanceAcceptanceTest {
    public static void main(String[] args) throws Exception {
        AtomicReference<LocalDate> observedStart = new AtomicReference<>();
        AtomicReference<LocalDate> observedEnd = new AtomicReference<>();
        var news = (com.stockmind.application.news.NewsProvider) (keyword, start, end, limit) ->
                List.of(
                        article("n1"), article("n2"), article("n3"), article("n4"));
        var announcements = (com.stockmind.application.announcement.AnnouncementProvider)
                (symbol, start, end, limit) -> {
                    observedStart.set(start);
                    observedEnd.set(end);
                    return List.of(new CompanyAnnouncement(
                            "a1", "2025年年度报告", "annual_report",
                            LocalDate.of(2025, 12, 31), "detail", "pdf"));
                };
        var financials = (com.stockmind.application.financial.FinancialReportProvider)
                (symbol, type, periods) -> List.of();
        String json = new ResearchEvidenceTools(news, financials, announcements).knowledgeSearch(
                Map.of("query", "药明康德 非经常性损益", "symbol", "603259",
                        "start_date", "2025-01-01", "end_date", "2025-12-31", "top_k", 3), "");
        var root = new ObjectMapper().readTree(json);
        require("success".equals(root.path("status").asText()), "knowledge_search调用失败");
        require(observedStart.get().equals(LocalDate.of(2025, 1, 1))
                        && observedEnd.get().equals(LocalDate.of(2025, 12, 31)),
                "显式历史窗口未传递给资料Provider");
        require("2025-01-01".equals(root.path("data").path("start_date").asText())
                        && "2025-12-31".equals(root.path("data").path("end_date").asText()),
                "工具输出改写了显式历史窗口");
        require("announcement".equals(root.path("data").path("items").get(0).path("type").asText()),
                "新闻结果不应挤掉官方公告");
        require(root.path("data").path("items").get(0).path("evidence_semantics")
                        .path("document_content_read").isBoolean()
                        && !root.path("data").path("items").get(0).path("evidence_semantics")
                        .path("document_content_read").asBoolean(),
                "公告元数据必须显式声明正文尚未读取");
        require(root.path("data").path("business_signals").isArray()
                        && root.path("data").path("business_signals").size() > 0
                        && "DISCOVERY_ONLY".equals(root.path("data").path("analysis_capability")
                                .path("resolution_mode").asText()),
                "跨来源检索必须将材料转换成发现型业务信号");
        String newsJson = new ResearchEvidenceTools(news, financials, announcements).newsSearch(
                Map.of("keyword", "药明康德 美国 制裁", "start_date", "2025-01-01",
                        "end_date", "2025-12-31"), "");
        var newsRoot = new ObjectMapper().readTree(newsJson);
        require(newsRoot.path("data").path("items").get(0).path("evidence_semantics")
                        .path("supports_intrinsic_value").isBoolean(),
                "新闻结果缺少机器可读证据边界");
        require(newsRoot.path("data").path("business_signals").isArray(),
                "新闻结果缺少统一业务信号");
        require("EVENT_DISCOVERY".equals(newsRoot.path("data").path("analysis_capability")
                        .path("capability").asText())
                        && "recent_event_impact".equals(newsRoot.path("data")
                        .path("analysis_capability").path("updates_agenda_ids").get(0).asText()),
                "新闻工具没有声明其能够更新的分析议题");
    }

    private static NewsArticle article(String id) {
        String title = "n1".equals(id) ? "CXO板块大涨" : "药明康德业绩分析";
        String summary = "n1".equals(id) ? "某证券指出药明康德风险有限且涨超10%" : "非经常性损益说明";
        return new NewsArticle(id, title, summary, "fixture",
                "https://example.test/" + id, LocalDateTime.of(2025, 6, 1, 12, 0));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
