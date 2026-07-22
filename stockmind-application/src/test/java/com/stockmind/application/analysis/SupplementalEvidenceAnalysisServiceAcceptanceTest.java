package com.stockmind.application.analysis;

import com.stockmind.application.news.NewsArticle;
import com.stockmind.domain.analysis.SignalDirection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 验证补充工具输出也遵循统一业务信号协议。 */
public final class SupplementalEvidenceAnalysisServiceAcceptanceTest {
    public static void main(String[] args) {
        SupplementalEvidenceAnalysisService service = new SupplementalEvidenceAnalysisService();
        LocalDate asOf = LocalDate.of(2026, 7, 21);
        var financial = service.financialTrendSignals(List.of(
                new FinancialTrendPoint(LocalDate.of(2026, 3, 31),
                        new BigDecimal("28.807"), new BigDecimal("26.677"), new BigDecimal("0.773")),
                new FinancialTrendPoint(LocalDate.of(2025, 3, 31),
                        new BigDecimal("20.956"), new BigDecimal("89.061"), new BigDecimal("0.870"))), asOf);
        require(financial.stream().anyMatch(signal -> signal.id().equals("financial_growth_momentum")
                        && signal.direction() == SignalDirection.NEGATIVE
                        && signal.summary().contains("-62.384")),
                "财务趋势应使用上年同报告期形成增长动量信号");

        var announcement = service.announcementMetadataSignal(
                30, LocalDate.of(2026, 4, 21), asOf);
        require(announcement.direction() == SignalDirection.UNKNOWN
                        && announcement.boundary().contains("正文尚未读取"),
                "公告元数据应形成待核实事件信号");

        var news = service.newsSignals(List.of(new NewsArticle(
                "1", "药明康德：机构认为未来增长稳定", "分析师预计收入增长",
                "fixture", "", LocalDateTime.of(2026, 7, 20, 9, 0))), "药明康德", asOf);
        require(news.stream().anyMatch(signal -> signal.id().equals("news_event_context")
                        && signal.direction() == SignalDirection.UNKNOWN),
                "相关新闻应先形成待验证事件线索");
        require(news.stream().anyMatch(signal -> signal.id().equals("news_market_opinion")),
                "机构观点应进入市场预期论点");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
