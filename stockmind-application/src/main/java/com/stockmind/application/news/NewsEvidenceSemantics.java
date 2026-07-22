package com.stockmind.application.news;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Assigns deterministic evidence boundaries to news so market commentary is not treated as fact. */
public final class NewsEvidenceSemantics {
    private static final Set<String> GENERIC_TERMS = Set.of(
            "股票", "公司", "行业", "市场", "最近", "近期", "美国", "制裁", "实体清单",
            "投资", "价值", "分析", "风险", "消息", "政策", "影响");
    private static final List<String> OPINION_MARKERS = List.of(
            "证券指出", "券商认为", "机构认为", "分析师认为", "研报认为", "观点认为",
            "预计", "看好", "评级", "目标价");
    private static final List<String> MARKET_REACTION_MARKERS = List.of(
            "股价上涨", "股价下跌", "涨超", "跌超", "大涨", "大跌", "涨停", "跌停",
            "反弹", "回调", "板块爆发", "利空出尽");

    private NewsEvidenceSemantics() { }

    /** Returns display-ready semantic tags without asserting that the article itself is official proof. */
    public static Map<String, Object> classify(NewsArticle article, String query) {
        String title = safe(article.title());
        String text = title + " " + safe(article.summary());
        String subject = primarySubject(query);
        boolean primarySubject = !subject.isBlank() && containsIgnoreCase(title, subject);
        List<String> tags = new ArrayList<>();
        tags.add("NEWS_REPORT");
        if (containsAny(text, OPINION_MARKERS)) tags.add("INSTITUTION_OPINION");
        if (containsAny(text, MARKET_REACTION_MARKERS)) tags.add("MARKET_REACTION");

        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("subject_relevance", primarySubject ? "PRIMARY_SUBJECT" : "MENTION_ONLY");
        semantics.put("evidence_tags", tags);
        semantics.put("officially_verified", false);
        semantics.put("supports_intrinsic_value", false);
        semantics.put("supports_risk_resolution", false);
        semantics.put("usable_as", tags.contains("INSTITUTION_OPINION")
                ? "MARKET_EXPECTATION" : "EVENT_DISCOVERY");
        semantics.put("verification_state", "AWAITING_PRIMARY_SOURCE");
        return semantics;
    }

    /** Metadata proves only that an official document exists; it does not prove document contents. */
    public static Map<String, Object> officialAnnouncementMetadata() {
        return Map.of(
                "subject_relevance", "PRIMARY_SUBJECT",
                "evidence_tags", List.of("OFFICIAL_ANNOUNCEMENT_METADATA"),
                "officially_verified", true,
                "document_content_read", false,
                "supports_intrinsic_value", false,
                "supports_risk_resolution", false,
                "usable_as", "PRIMARY_DOCUMENT_DISCOVERY",
                "verification_state", "DOCUMENT_CONTENT_NOT_READ");
    }

    private static String primarySubject(String query) {
        if (query == null) return "";
        for (String term : query.trim().split("[\\s,，。；;:：、/|]+")) {
            String value = term.trim();
            if (value.length() >= 2 && !GENERIC_TERMS.contains(value)
                    && !value.matches("\\d{4}年?") && !value.matches("\\d{6}")) {
                return value;
            }
        }
        return "";
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) if (text.contains(marker)) return true;
        return false;
    }

    private static boolean containsIgnoreCase(String text, String value) {
        return text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
