package com.stockmind.application.analysis;

import com.stockmind.domain.analysis.InvestmentStance;
import com.stockmind.domain.analysis.InvestmentThesisType;
import com.stockmind.domain.analysis.AnalysisCapability;
import com.stockmind.domain.analysis.ConclusionSensitivity;
import com.stockmind.domain.analysis.DecisionImportance;
import com.stockmind.domain.analysis.EvidenceResolutionStatus;
import com.stockmind.domain.analysis.SignalDirection;
import com.stockmind.domain.analysis.ThesisStatus;
import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorCategoryResult;
import com.stockmind.domain.factor.FactorCoverage;
import com.stockmind.domain.factor.FactorDirection;
import com.stockmind.domain.factor.FactorQuality;
import com.stockmind.domain.factor.FactorScore;
import com.stockmind.domain.factor.FactorValue;
import com.stockmind.domain.factor.InvestmentAssessmentGuardrails;
import com.stockmind.domain.factor.InvestmentConclusionStrength;
import com.stockmind.domain.factor.InvestmentStyle;
import com.stockmind.domain.factor.StockFactorProfile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 使用线上药明康德场景验证“事实到投资论点”的转换。 */
public final class StockInvestmentAnalysisServiceAcceptanceTest {
    public static void main(String[] args) {
        StockInvestmentAnalysisService service = new StockInvestmentAnalysisService();
        var analysis = service.analyze(profile());

        require(analysis.stance() == InvestmentStance.WATCH,
                "中等评分、增长分化且盈利归因未知时应形成观察立场");
        require(direction(analysis, "valuation_history") == SignalDirection.NEUTRAL,
                "53.13%历史分位应解释为估值中部");
        require(direction(analysis, "growth_level") == SignalDirection.POSITIVE,
                "收入和利润均为正增长时，增长水平应为正面");
        require(direction(analysis, "growth_momentum") == SignalDirection.NEGATIVE,
                "同报告期利润增速下降62.384个百分点应形成负面增长动量");
        require(direction(analysis, "earnings_attribution") == SignalDirection.UNKNOWN,
                "缺少扣非利润时应保持盈利归因未知，而不是自动判为负面");
        require(analysis.theses().stream().anyMatch(thesis ->
                        thesis.type() == InvestmentThesisType.GROWTH
                                && thesis.status() == ThesisStatus.MIXED),
                "正增长与增速下降应汇总为混合增长论点");
        require(analysis.theses().stream().anyMatch(thesis ->
                        thesis.type() == InvestmentThesisType.VALUATION
                                && thesis.status() == ThesisStatus.NEUTRAL),
                "弱同行正面信号不应覆盖强历史中性估值信号");
        require(analysis.theses().stream().anyMatch(thesis ->
                        thesis.type() == InvestmentThesisType.EARNINGS_QUALITY
                                && thesis.conclusion().contains("负面信号")
                                && thesis.conclusion().contains("未解决")),
                "负面加未知的盈利质量论点不应描述成正负信号并存");
        require(analysis.unresolvedQuestions().stream().anyMatch(value -> value.contains("利润增长来源")),
                "盈利归因缺口应进入未知问题");
        require(analysis.unresolvedIssues().stream().anyMatch(issue ->
                        issue.id().equals("earnings_attribution")
                                && issue.resolutionStatus().name().equals("NOT_AVAILABLE")
                                && issue.requiredEvidence().contains("扣非净利润")),
                "未知问题应说明所需证据和当前工具解决能力");
        require(analysis.changeConditions().stream().anyMatch(value -> value.contains("扣非利润")),
                "盈利归因未知时应给出改变结论的条件");
        var valuationAgenda = analysis.analysisAgenda().stream()
                .filter(item -> item.id().equals("valuation_safety_margin"))
                .findFirst().orElseThrow();
        require(valuationAgenda.decisionWeight() == DecisionImportance.HIGH
                        && valuationAgenda.conclusionSensitivity() == ConclusionSensitivity.HIGH,
                "估值安全边际应作为高权重、高敏感度分析议题");
        require(valuationAgenda.evidenceNeeds().stream().anyMatch(need ->
                        need.capability() == AnalysisCapability.FORWARD_VALUATION
                                && need.resolutionStatus() == EvidenceResolutionStatus.AVAILABLE)
                        && valuationAgenda.evidenceNeeds().stream().anyMatch(need ->
                        need.capability() == AnalysisCapability.SCENARIO_VALUATION
                                && need.resolutionStatus() == EvidenceResolutionStatus.AVAILABLE),
                "估值议题应暴露前向估值和情景估值能力，而不是直接指定工具顺序");
        var qualityAgenda = analysis.analysisAgenda().stream()
                .filter(item -> item.id().equals("earnings_quality_durability"))
                .findFirst().orElseThrow();
        require(qualityAgenda.evidenceNeeds().stream().anyMatch(need ->
                        need.capability() == AnalysisCapability.FINANCIAL_TREND
                                && need.resolutionStatus() == EvidenceResolutionStatus.AVAILABLE)
                        && qualityAgenda.evidenceNeeds().stream().anyMatch(need ->
                        need.capability() == AnalysisCapability.EARNINGS_ATTRIBUTION
                                && need.resolutionStatus() == EvidenceResolutionStatus.NOT_AVAILABLE),
                "字段不可得时仍应保留能够增加盈利质量判断的替代证据能力");
        require(!analysis.analysisReadiness().stopEligible()
                        && analysis.analysisReadiness().openHighSensitivityAgendaIds()
                        .contains("growth_sustainability"),
                "基线因子画像后仍有高敏感度可查议题时不应直接满足停止条件");
    }

    private static SignalDirection direction(
            com.stockmind.domain.analysis.StockInvestmentAnalysis analysis, String id) {
        return analysis.signals().stream().filter(signal -> id.equals(signal.id()))
                .findFirst().orElseThrow().direction();
    }

    private static StockFactorProfile profile() {
        LocalDate asOf = LocalDate.of(2026, 7, 21);
        Map<FactorCategory, FactorCategoryResult> categories = new EnumMap<>(FactorCategory.class);
        categories.put(FactorCategory.VALUATION, result(FactorCategory.VALUATION, List.of(
                factor("pe_ttm", "18.74", asOf, asOf),
                factor("pe_ttm_percentile_3y", "53.13", asOf, asOf),
                factor("peer_pe_premium_pct", "-66.54", asOf, asOf))));
        categories.put(FactorCategory.GROWTH, result(FactorCategory.GROWTH, List.of(
                factor("latest_annual_revenue_yoy_pct", "15.837", asOf, LocalDate.of(2025, 12, 31)),
                factor("latest_annual_net_profit_yoy_pct", "102.645", asOf, LocalDate.of(2025, 12, 31)),
                factor("latest_quarter_revenue_yoy_pct", "28.807", asOf, LocalDate.of(2026, 3, 31)),
                factor("latest_quarter_net_profit_yoy_pct", "26.677", asOf, LocalDate.of(2026, 3, 31)),
                factor("comparable_growth_change_pct", "-62.384", asOf, LocalDate.of(2026, 3, 31)))));
        categories.put(FactorCategory.QUALITY, result(FactorCategory.QUALITY, List.of(
                missing("non_recurring_profit_ratio_pct", asOf),
                factor("cash_to_net_profit_ratio", "0.773", asOf, LocalDate.of(2026, 3, 31)))));
        categories.put(FactorCategory.EXPECTATION_REVISION,
                new FactorCategoryResult(FactorCategory.EXPECTATION_REVISION, List.of(), List.of(),
                        List.of("forecast_growth_state=STABLE_OR_GROWING", "expectation_revision_state=STABLE")));
        categories.put(FactorCategory.MOMENTUM_RISK, result(FactorCategory.MOMENTUM_RISK, List.of()));
        categories.put(FactorCategory.SHAREHOLDER_RETURN, result(FactorCategory.SHAREHOLDER_RETURN, List.of()));

        Map<FactorCategory, FactorScore> scores = new EnumMap<>(FactorCategory.class);
        for (FactorCategory category : FactorCategory.values()) {
            scores.put(category, new FactorScore(category, BigDecimal.valueOf(55), BigDecimal.valueOf(90),
                    FactorQuality.VALID, List.of()));
        }
        return new StockFactorProfile(
                "request", "SH603259", "药明康德", asOf, asOf, "stock-factor-v1",
                InvestmentStyle.BALANCED,
                new FactorCoverage(BigDecimal.valueOf(89.23), FactorQuality.VALID, Map.of()),
                BigDecimal.valueOf(57.77), categories, scores,
                new InvestmentAssessmentGuardrails("PARTIAL", InvestmentConclusionStrength.TENTATIVE,
                        true, Map.of(), List.of("EARNINGS_ATTRIBUTION_UNRESOLVED",
                                "COMPARABLE_PEER_SET_NOT_VALIDATED")),
                List.of(), List.of(), List.of(), List.of(), List.of("fixture"));
    }

    private static FactorCategoryResult result(FactorCategory category, List<FactorValue> values) {
        return new FactorCategoryResult(category, values, List.of(), List.of());
    }

    private static FactorValue factor(String name, String value, LocalDate asOf, LocalDate reportPeriod) {
        BigDecimal number = new BigDecimal(value);
        return new FactorValue(name, number, number, "", FactorDirection.NEUTRAL,
                asOf, reportPeriod, List.of("fixture"), "fixture", FactorQuality.VALID, List.of(), "");
    }

    private static FactorValue missing(String name, LocalDate asOf) {
        return new FactorValue(name, null, null, "", FactorDirection.NEUTRAL,
                asOf, null, List.of(), "fixture", FactorQuality.MISSING, List.of(), "缺少数据");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
