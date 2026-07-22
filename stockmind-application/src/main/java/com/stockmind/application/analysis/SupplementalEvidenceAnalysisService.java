package com.stockmind.application.analysis;

import com.stockmind.application.news.NewsArticle;
import com.stockmind.application.news.NewsEvidenceSemantics;
import com.stockmind.domain.analysis.AnalysisEvidence;
import com.stockmind.domain.analysis.BusinessSignal;
import com.stockmind.domain.analysis.InvestmentThesisType;
import com.stockmind.domain.analysis.SignalDirection;
import com.stockmind.domain.analysis.SignalStrength;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将财务趋势、新闻和公告等补充材料转换成统一业务信号。
 * 补充工具只描述自己能够更新的论点，不代替综合投资立场。
 */
public final class SupplementalEvidenceAnalysisService {
    private static final BigDecimal MATERIAL_GROWTH_CHANGE = BigDecimal.valueOf(15);

    /** 从多期财务数据中只比较同一报告期，避免年度与季度累计口径交叉。 */
    public List<BusinessSignal> financialTrendSignals(
            List<FinancialTrendPoint> points, LocalDate asOf) {
        if (points == null || points.isEmpty()) return List.of();
        FinancialTrendPoint latest = points.get(0);
        List<BusinessSignal> signals = new ArrayList<>();
        if (latest.revenueYoyPct() != null && latest.netProfitYoyPct() != null) {
            SignalDirection direction = latest.revenueYoyPct().signum() > 0
                    && latest.netProfitYoyPct().signum() > 0
                    ? SignalDirection.POSITIVE
                    : latest.revenueYoyPct().signum() < 0 && latest.netProfitYoyPct().signum() < 0
                            ? SignalDirection.NEGATIVE : SignalDirection.NEUTRAL;
            signals.add(signal("financial_growth_level", InvestmentThesisType.GROWTH, direction,
                    SignalStrength.STRONG,
                    "最新报告期营收同比" + signed(latest.revenueYoyPct())
                            + "%，净利润同比" + signed(latest.netProfitYoyPct()) + "%",
                    "同一报告期收入和利润同比反映当前增长水平",
                    "增长水平不直接解释利润来源",
                    evidence("最新报告期营收同比" + signed(latest.revenueYoyPct())
                                    + "%，净利润同比" + signed(latest.netProfitYoyPct()) + "%",
                            "财报趋势", asOf, latest.reportPeriod(), "同报告期同比")));
        }

        FinancialTrendPoint comparable = points.stream()
                .filter(point -> latest.reportPeriod() != null
                        && latest.reportPeriod().minusYears(1).equals(point.reportPeriod()))
                .findFirst().orElse(null);
        if (comparable != null && latest.netProfitYoyPct() != null
                && comparable.netProfitYoyPct() != null) {
            BigDecimal change = latest.netProfitYoyPct().subtract(comparable.netProfitYoyPct());
            SignalDirection direction = change.compareTo(MATERIAL_GROWTH_CHANGE.negate()) <= 0
                    ? SignalDirection.NEGATIVE
                    : change.compareTo(MATERIAL_GROWTH_CHANGE) >= 0
                            ? SignalDirection.POSITIVE : SignalDirection.NEUTRAL;
            signals.add(signal("financial_growth_momentum", InvestmentThesisType.GROWTH, direction,
                    SignalStrength.MEDIUM,
                    "净利润同比增速较上年同报告期变化" + signed(change) + "个百分点",
                    "最新报告期与上年同一报告期比较",
                    "增速变化不解释变化原因",
                    evidence("净利润同比增速较上年同报告期变化" + signed(change) + "个百分点",
                            "财报趋势", asOf, latest.reportPeriod(), "同报告期同比变化")));
        }
        if (latest.cashToNetProfitRatio() != null) {
            SignalDirection direction = latest.cashToNetProfitRatio().compareTo(BigDecimal.ONE) >= 0
                    ? SignalDirection.POSITIVE
                    : latest.cashToNetProfitRatio().compareTo(BigDecimal.valueOf(0.8)) < 0
                            ? SignalDirection.NEGATIVE : SignalDirection.NEUTRAL;
            signals.add(signal("financial_cash_conversion", InvestmentThesisType.EARNINGS_QUALITY,
                    direction, SignalStrength.MEDIUM,
                    "经营现金流与归母净利润比为" + number(latest.cashToNetProfitRatio()),
                    "现金利润比反映当期利润的现金覆盖程度",
                    "单一报告期不能代表长期盈利质量",
                    evidence("经营现金流与归母净利润比为" + number(latest.cashToNetProfitRatio()),
                            "财报趋势", asOf, latest.reportPeriod(), "经营现金流/归母净利润")));
        }
        return signals;
    }

    /** 新闻首先是事件线索或市场观点；未经过官方材料确认时保持为待验证信号。 */
    public List<BusinessSignal> newsSignals(
            List<NewsArticle> articles, String keyword, LocalDate asOf) {
        if (articles == null || articles.isEmpty()) return List.of();
        long primary = 0;
        long opinions = 0;
        for (NewsArticle article : articles) {
            Map<String, Object> semantics = NewsEvidenceSemantics.classify(article, keyword);
            if ("PRIMARY_SUBJECT".equals(String.valueOf(semantics.get("subject_relevance")))) primary++;
            Object rawTags = semantics.get("evidence_tags");
            if (rawTags instanceof List<?> tags && tags.contains("INSTITUTION_OPINION")) opinions++;
        }
        List<BusinessSignal> signals = new ArrayList<>();
        if (primary > 0) {
            signals.add(signal("news_event_context", InvestmentThesisType.EVENT_RISK,
                    SignalDirection.UNKNOWN, SignalStrength.WEAK,
                    "检索到" + primary + "条与主体直接相关的新闻线索",
                    "新闻用于发现可能影响投资论点的事件",
                    "事件内容及影响仍需公司公告或其他一手材料确认",
                    evidence("检索到" + primary + "条与主体直接相关的新闻线索",
                            "新闻", asOf, null, "公开新闻线索")));
        }
        if (opinions > 0) {
            signals.add(signal("news_market_opinion", InvestmentThesisType.EXPECTATIONS,
                    SignalDirection.NEUTRAL, SignalStrength.WEAK,
                    "检索结果包含" + opinions + "条机构或分析观点",
                    "机构观点用于观察市场预期",
                    "观点方向需要结合具体报告内容判断",
                    evidence("检索结果包含" + opinions + "条机构或分析观点",
                            "新闻", asOf, null, "机构观点样本")));
        }
        return signals;
    }

    /** 公告元数据只确认文件存在，因此形成事件待核实信号而不是事件方向。 */
    public BusinessSignal announcementMetadataSignal(
            int count, LocalDate startDate, LocalDate endDate) {
        return signal("announcement_metadata", InvestmentThesisType.EVENT_RISK,
                SignalDirection.UNKNOWN, SignalStrength.MEDIUM,
                "查询窗口内检索到" + count + "份公司公告元数据",
                "公告标题和链接用于定位一手材料",
                "公告正文尚未读取，事件内容及其经营影响仍待确认",
                evidence("查询窗口内检索到" + count + "份公司公告元数据",
                        "公司公告", endDate, null,
                        startDate + "至" + endDate + "公告元数据"));
    }

    private BusinessSignal signal(
            String id,
            InvestmentThesisType thesis,
            SignalDirection direction,
            SignalStrength strength,
            String summary,
            String rationale,
            String boundary,
            AnalysisEvidence evidence) {
        return new BusinessSignal(id, thesis, direction, strength, summary, rationale, boundary,
                List.of(evidence));
    }

    private AnalysisEvidence evidence(
            String fact, String sourceType, LocalDate asOf, LocalDate reportPeriod, String basis) {
        return new AnalysisEvidence(fact, sourceType, asOf, reportPeriod, basis, List.of());
    }

    private String signed(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + number(value);
    }

    private String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
