package com.stockmind.bootstrap.business.tool;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.announcement.AnnouncementProvider;
import com.stockmind.application.analysis.SupplementalEvidenceAnalysisService;
import com.stockmind.application.market.StockTimeWindowResolver;
import com.stockmind.application.news.NewsEvidenceSemantics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Official corporate-event evidence tools. */
@Component
public class CorporateEventTools extends StockToolSupport {
    private static final Logger LOG = LoggerFactory.getLogger(CorporateEventTools.class);
    private static final String SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$"},"start_date":{"type":"string","format":"date"},"end_date":{"type":"string","format":"date"},"time_window":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":100}},"required":["symbol"],"additionalProperties":false}
            """;
    private final AnnouncementProvider announcementProvider;
    private final SupplementalEvidenceAnalysisService analysis = new SupplementalEvidenceAnalysisService();

    public CorporateEventTools(AnnouncementProvider announcementProvider) {
        this.announcementProvider = announcementProvider;
    }

    @AgentTool(name = "company_announcements", title = "上市公司公告检索",
            namespace = "finance.events", category = "announcement_search",
            tags = {"stock", "announcement", "official", "readonly"}, inputSchema = SCHEMA,
            description = "检索巨潮资讯公司公告，返回标题、发布日期、原文链接和事件风险业务信号；未读取正文的公告会标记为待核实。")
    public String companyAnnouncements(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("company_announcements", StockToolError.SYMBOL_REQUIRED);
        try {
            var window = StockTimeWindowResolver.resolveNews(asString(input.get("start_date")),
                    asString(input.get("end_date")), asString(input.get("time_window")));
            var announcements = announcementProvider.search(symbol, window.startDate(), window.endDate(),
                    Math.min(100, positiveInt(input.get("limit"), 30)));
            List<Map<String, Object>> items = new ArrayList<>();
            announcements.forEach(a -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", a.id());
                item.put("title", a.title());
                item.put("type", a.type());
                item.put("published_at", a.publishedDate().toString());
                item.put("detail_url", a.detailUrl());
                item.put("pdf_url", a.pdfUrl());
                item.put("evidence_semantics", NewsEvidenceSemantics.officialAnnouncementMetadata());
                items.add(item);
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("start_date", window.startDate().toString());
            data.put("end_date", window.endDate().toString());
            data.put("source", "cninfo_official_api");
            data.put("result_quality", items.isEmpty() ? "EMPTY_RESULT" : "HIGH_INFORMATION");
            data.put("coverage", Map.of("returned_count", items.size(), "window_fully_covered", true));
            data.put("items", items);
            data.put("business_signals", List.of(StockBusinessSignalMapper.map(
                    analysis.announcementMetadataSignal(items.size(), window.startDate(), window.endDate()))));
            data.put("analysis_capability", Map.of(
                    "capability", "EVENT_DISCOVERY",
                    "resolution_mode", "DISCOVERY_ONLY",
                    "updates_agenda_ids", List.of("recent_event_impact")));
            return success("company_announcements", data,
                    "[\"公告来自巨潮官方接口\",\"公告日期已按请求窗口过滤\",\"提供原文链接\"]");
        } catch (IllegalArgumentException e) {
            return fail("company_announcements", StockToolError.INVALID_TIME_WINDOW);
        } catch (Exception e) {
            LOG.warn("Company announcement provider failed", e);
            return fail("company_announcements", StockToolError.ANNOUNCEMENT_SERVICE_UNAVAILABLE);
        }
    }
}
