package com.stockmind.application.news;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Ensures company mentions, institution opinions and price reactions stay semantically separated. */
public final class NewsEvidenceSemanticsAcceptanceTest {
    public static void main(String[] args) {
        NewsArticle opinion = new NewsArticle("1", "CXO板块大涨",
                "华泰证券指出清单实际影响较小，药明康德涨超10%", "fixture", "", LocalDateTime.now());
        Map<String, Object> opinionSemantics = NewsEvidenceSemantics.classify(
                opinion, "药明康德 美国 制裁 实体清单");
        require("MENTION_ONLY".equals(opinionSemantics.get("subject_relevance")),
                "标题未以标的为主体时必须标记MENTION_ONLY");
        List<?> tags = (List<?>) opinionSemantics.get("evidence_tags");
        require(tags.contains("INSTITUTION_OPINION") && tags.contains("MARKET_REACTION"),
                "机构观点和市场反应标签缺失");
        require(Boolean.FALSE.equals(opinionSemantics.get("supports_intrinsic_value"))
                        && Boolean.FALSE.equals(opinionSemantics.get("supports_risk_resolution")),
                "新闻不得授权内在价值或风险解除结论");

        NewsArticle subject = new NewsArticle("2", "药明康德就清单事项提起诉讼",
                "公司披露已采取法律行动", "fixture", "", LocalDateTime.now());
        require("PRIMARY_SUBJECT".equals(NewsEvidenceSemantics.classify(
                        subject, "药明康德 美国 制裁").get("subject_relevance")),
                "标题以标的为主体时应标记PRIMARY_SUBJECT");
        require(Boolean.FALSE.equals(NewsEvidenceSemantics.officialAnnouncementMetadata()
                        .get("document_content_read")),
                "公告元数据不得冒充已读取正文");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
