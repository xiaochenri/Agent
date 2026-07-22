package com.stockmind.bootstrap.business.tool;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.analysis.StockInvestmentAnalysisService;
import com.stockmind.application.factor.FactorPolicy;
import com.stockmind.application.factor.StockFactorProfileService;
import com.stockmind.domain.analysis.AnalysisEvidence;
import com.stockmind.domain.analysis.AnalysisAgendaItem;
import com.stockmind.domain.analysis.AnalysisCapability;
import com.stockmind.domain.analysis.AnalysisEvidenceNeed;
import com.stockmind.domain.analysis.BusinessSignal;
import com.stockmind.domain.analysis.InvestmentThesis;
import com.stockmind.domain.analysis.StockInvestmentAnalysis;
import com.stockmind.domain.analysis.UnresolvedIssue;
import com.stockmind.domain.factor.InvestmentStyle;
import com.stockmind.domain.factor.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Thin Agent adapter; all factor formulas live in stockmind-application. */
@Component
public final class FactorAnalysisTools extends StockToolSupport {
    private static final Logger LOG = LoggerFactory.getLogger(FactorAnalysisTools.class);
    private static final String PROFILE_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^((SH|SZ|BJ)?\\\\d{6})$"},"as_of":{"type":"string","format":"date"},"style":{"type":"string","enum":["QUALITY_VALUE","BALANCED","GROWTH","DEFENSIVE_INCOME"]},"detail_level":{"type":"string","enum":["COMPACT","FULL"]}},"required":["symbol"],"additionalProperties":false}
            """;
    private final StockFactorProfileService profileService;
    private final StockInvestmentAnalysisService analysisService;

    /** Creates the thin tool adapter around the deterministic application service. */
    public FactorAnalysisTools(
            StockFactorProfileService profileService,
            StockInvestmentAnalysisService analysisService) {
        this.profileService = profileService;
        this.analysisService = analysisService;
    }

    /** Validates input and defaults to a compact profile that keeps decision gates visible. */
    @AgentTool(name = "stock_factor_profile", title = "单股确定性因子画像",
            namespace = "finance.factor", category = "factor_profile",
            tags = {"stock", "factor", "valuation", "quality", "growth", "readonly"},
            inputSchema = PROFILE_SCHEMA,
            description = "综合投资价值分析工具。按统一as_of快照计算因子，并将指标转换成业务信号、投资论点和投资立场；默认COMPACT，只有审计因子明细时使用FULL。")
    public String stockFactorProfile(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("stock_factor_profile", StockToolError.SYMBOL_REQUIRED);
        try {
            LocalDate asOf = asString(input.get("as_of")).isBlank()
                    ? LocalDate.now(StockToolSupport.CHINA_ZONE) : LocalDate.parse(asString(input.get("as_of")));
            InvestmentStyle style = asString(input.get("style")).isBlank()
                    ? InvestmentStyle.BALANCED : InvestmentStyle.valueOf(asString(input.get("style")));
            boolean fullDetail = "FULL".equals(asString(input.get("detail_level")));
            return success("stock_factor_profile", toData(profileService.analyze(symbol, asOf, style), fullDetail),
                    "[\"六类因子均由业务代码确定性计算\",\"综合评分受覆盖率门禁约束\",\"结果不构成买卖建议\"]");
        } catch (IllegalArgumentException e) {
            return fail("stock_factor_profile", StockToolError.INVALID_FACTOR_PROFILE_INPUT);
        } catch (Exception e) {
            LOG.warn("Stock factor profile failed, exceptionType={}, message={}",
                    e.getClass().getName(), e.getMessage());
            LOG.debug("Stock factor profile failure details", e);
            return fail("stock_factor_profile", StockToolError.FACTOR_PROFILE_UNAVAILABLE);
        }
    }

    private Map<String, Object> toData(StockFactorProfile profile, boolean fullDetail) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("request_id", profile.requestId()); data.put("symbol", profile.symbol());
        data.put("name", profile.name()); data.put("as_of", profile.asOf().toString());
        data.put("effective_trade_date", profile.effectiveTradeDate().toString());
        data.put("policy_version", profile.policyVersion()); data.put("style", profile.style().name());
        data.put("coverage_pct", profile.coverage().coveragePct());
        data.put("coverage_quality", profile.coverage().quality().name());
        data.put("overall_score", profile.overallScore());
        data.put("score_scale", "0-100");
        data.put("score_breakdown", scoreBreakdown(profile));
        StockInvestmentAnalysis analysis = analysisService.analyze(profile);
        // 高频决策字段同时放在顶层，确保上下文压缩后模型仍能看到完整业务信号。
        data.put("investment_stance", analysis.stance().name());
        data.put("analysis_confidence", analysis.confidence());
        data.put("analysis_judgment", analysis.judgment());
        data.put("analysis_signals", analysis.signals().stream().map(this::signalMap).toList());
        data.put("investment_theses", analysis.theses().stream().map(this::thesisMap).toList());
        data.put("analysis_agenda", analysis.analysisAgenda().stream().map(this::agendaMap).toList());
        data.put("analysis_readiness", readinessMap(analysis));
        data.put("unresolved_issues", analysis.unresolvedIssues().stream().map(this::issueMap).toList());
        data.put("unresolved_questions", analysis.unresolvedQuestions());
        data.put("change_conditions", analysis.changeConditions());
        data.put("key_metrics", keyMetrics(profile));
        data.put("investment_analysis", analysisMap(analysis));
        Map<String, Object> categories = new java.util.LinkedHashMap<>();
        profile.categories().forEach((category, result) -> {
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("score", scoreMap(profile.categoryScores().get(category)));
            value.put("factors", result.factors().stream().map(this::factorMap).toList());
            value.put("warnings", result.warnings().stream().map(Enum::name).toList());
            value.put("signals", result.signals()); categories.put(category.name().toLowerCase(), value);
        });
        data.put("detail_level", fullDetail ? "FULL" : "COMPACT");
        if (fullDetail) data.put("categories", categories);
        data.put("category_summary", categorySummary(profile));
        data.put("warnings", profile.warnings().stream().map(Enum::name).toList());
        data.put("limitations", profile.limitations()); data.put("sources", profile.sources());
        return data;
    }

    /** Compact summaries keep decision-critical fields visible in the model context. */
    private Map<String, Object> categorySummary(StockFactorProfile profile) {
        Map<String, Object> summaries = new java.util.LinkedHashMap<>();
        profile.categories().forEach((category, result) -> summaries.put(category.name().toLowerCase(),
                Map.of("score", scoreMap(profile.categoryScores().get(category)),
                        "warnings", result.warnings().stream().map(Enum::name).toList(),
                        "signals", result.signals())));
        return summaries;
    }

    /** Makes the coverage-adjusted weighted score directly auditable in the user-facing tool data. */
    private List<Map<String, Object>> scoreBreakdown(StockFactorProfile profile) {
        FactorPolicy policy = FactorPolicy.of(profile.style());
        Map<FactorCategory, java.math.BigDecimal> effectiveWeights =
                profile.coverage().effectiveWeights();
        java.math.BigDecimal totalEffectiveWeight = effectiveWeights.values().stream()
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (FactorCategory category : FactorCategory.values()) {
            FactorScore score = profile.categoryScores().get(category);
            java.math.BigDecimal configuredWeight = policy.weights().getOrDefault(
                    category, java.math.BigDecimal.ZERO);
            java.math.BigDecimal effectiveWeight = effectiveWeights.getOrDefault(
                    category, java.math.BigDecimal.ZERO);
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("category", category.name());
            item.put("configured_weight_pct", configuredWeight);
            item.put("category_score", score == null ? null : score.score());
            item.put("coverage_pct", score == null ? null : score.coveragePct());
            item.put("effective_weight_pct", effectiveWeight);
            item.put("contribution_points", score == null || score.score() == null
                    || totalEffectiveWeight.signum() == 0 ? null
                    : score.score().multiply(effectiveWeight).divide(
                            totalEffectiveWeight, 2, java.math.RoundingMode.HALF_UP));
            result.add(item);
        }
        return result;
    }

    /** Promotes decision-critical factors so the model need not rediscover them in category arrays. */
    private Map<String, Object> keyMetrics(StockFactorProfile profile) {
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        putKeyFactor(metrics, "pe_ttm", profile, FactorCategory.VALUATION, "pe_ttm");
        putKeyFactor(metrics, "pe_ttm_percentile_3y", profile,
                FactorCategory.VALUATION, "pe_ttm_percentile_3y");
        putKeyFactor(metrics, "pe_ttm_percentile_5y", profile,
                FactorCategory.VALUATION, "pe_ttm_percentile_5y");
        putKeyFactor(metrics, "forward_pe_current_fiscal_year", profile,
                FactorCategory.VALUATION, "forward_pe");
        putKeyFactor(metrics, "latest_annual_revenue_yoy_pct", profile,
                FactorCategory.GROWTH, "latest_annual_revenue_yoy_pct");
        putKeyFactor(metrics, "latest_annual_net_profit_yoy_pct", profile,
                FactorCategory.GROWTH, "latest_annual_net_profit_yoy_pct");
        putKeyFactor(metrics, "latest_quarter_revenue_yoy_pct", profile,
                FactorCategory.GROWTH, "latest_quarter_revenue_yoy_pct");
        putKeyFactor(metrics, "latest_quarter_net_profit_yoy_pct", profile,
                FactorCategory.GROWTH, "latest_quarter_net_profit_yoy_pct");
        putKeyFactor(metrics, "comparable_growth_change_pct", profile,
                FactorCategory.GROWTH, "comparable_growth_change_pct");
        putKeyFactor(metrics, "non_recurring_profit_ratio_pct", profile,
                FactorCategory.QUALITY, "non_recurring_profit_ratio_pct");
        putKeyFactor(metrics, "cash_to_net_profit_ratio", profile,
                FactorCategory.QUALITY, "cash_to_net_profit_ratio");
        putKeyFactor(metrics, "roe_pct", profile, FactorCategory.QUALITY, "roe_pct");
        putKeyFactor(metrics, "peer_pe_premium_pct", profile,
                FactorCategory.VALUATION, "peer_pe_premium_pct");
        putKeyFactor(metrics, "peer_pb_premium_pct", profile,
                FactorCategory.VALUATION, "peer_pb_premium_pct");
        metrics.put("growth_states", profile.categories().get(FactorCategory.GROWTH).signals());
        metrics.put("expectation_revision_states",
                profile.categories().get(FactorCategory.EXPECTATION_REVISION).signals());
        metrics.put("peer_valuation_status", profile.categories().get(FactorCategory.VALUATION)
                .factors().stream().filter(value -> value.name().equals("peer_pe_premium_pct"))
                .findFirst().map(value -> value.quality().name()).orElse("MISSING"));
        return metrics;
    }

    /** 将中间层对象转换成稳定的工具协议，避免模型再次从原始指标推导业务含义。 */
    private Map<String, Object> analysisMap(StockInvestmentAnalysis analysis) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("stance", analysis.stance().name());
        value.put("confidence", analysis.confidence());
        value.put("time_horizon", analysis.timeHorizon());
        value.put("judgment", analysis.judgment());
        value.put("signals", analysis.signals().stream().map(this::signalMap).toList());
        value.put("theses", analysis.theses().stream().map(this::thesisMap).toList());
        value.put("analysis_agenda", analysis.analysisAgenda().stream().map(this::agendaMap).toList());
        value.put("analysis_readiness", readinessMap(analysis));
        value.put("unresolved_issues", analysis.unresolvedIssues().stream().map(this::issueMap).toList());
        value.put("unresolved_questions", analysis.unresolvedQuestions());
        value.put("change_conditions", analysis.changeConditions());
        return value;
    }

    /** 将投资议题与当前工具能力连接，但不指定唯一工具或固定调用顺序。 */
    private Map<String, Object> agendaMap(AnalysisAgendaItem item) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("agenda_id", item.id());
        value.put("thesis", item.thesis().name());
        value.put("question", item.question());
        value.put("current_judgment", item.currentJudgment().name());
        value.put("decision_weight", item.decisionWeight().name());
        value.put("evidence_coverage", item.evidenceCoverage().name());
        value.put("contradiction", item.contradiction());
        value.put("evidence_needs", item.evidenceNeeds().stream().map(this::evidenceNeedMap).toList());
        value.put("conclusion_sensitivity", item.conclusionSensitivity().name());
        return value;
    }

    private Map<String, Object> evidenceNeedMap(AnalysisEvidenceNeed need) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("required_evidence", need.evidence());
        value.put("capability", need.capability().name());
        value.put("resolution_status", need.resolutionStatus().name());
        value.put("expected_contribution", need.expectedContribution());
        value.put("candidate_tools", candidateTools(need.capability()));
        return value;
    }

    /** 能力可以对应多个候选工具；空列表明确表示当前没有实现该能力。 */
    private List<String> candidateTools(AnalysisCapability capability) {
        return switch (capability) {
            case FORWARD_VALUATION -> List.of("analyst_consensus_forecast");
            case SCENARIO_VALUATION -> List.of("scenario_valuation_analysis");
            case FINANCIAL_TREND -> List.of("financial_trend_analysis");
            case PEER_VALUATION_CONTEXT -> List.of("peer_valuation_comparison");
            case RESEARCH_CONTEXT -> List.of("research_report_search");
            case EVENT_DISCOVERY -> List.of("news_search", "company_announcements");
            case STRICT_PEER_VALIDATION, EARNINGS_ATTRIBUTION, PRIMARY_EVENT_VERIFICATION -> List.of();
        };
    }

    private Map<String, Object> readinessMap(StockInvestmentAnalysis analysis) {
        return Map.of(
                "baseline_complete", analysis.analysisReadiness().baselineComplete(),
                "stop_eligible", analysis.analysisReadiness().stopEligible(),
                "open_high_sensitivity_agenda_ids",
                        analysis.analysisReadiness().openHighSensitivityAgendaIds(),
                "reason", analysis.analysisReadiness().reason());
    }

    /** 业务信号同时携带解释和证据边界，供模型形成可追溯的自然语言论据。 */
    private Map<String, Object> signalMap(BusinessSignal signal) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("signal_id", signal.id());
        value.put("thesis", signal.thesis().name());
        value.put("direction", signal.direction().name());
        value.put("strength", signal.strength().name());
        value.put("summary", signal.summary());
        value.put("rationale", signal.rationale());
        value.put("boundary", signal.boundary());
        value.put("evidence", signal.evidence().stream().map(this::analysisEvidenceMap).toList());
        return value;
    }

    private Map<String, Object> analysisEvidenceMap(AnalysisEvidence evidence) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("fact", evidence.fact());
        value.put("source_type", evidence.sourceType());
        value.put("as_of", evidence.asOf() == null ? null : evidence.asOf().toString());
        value.put("report_period", evidence.reportPeriod() == null ? null : evidence.reportPeriod().toString());
        value.put("basis", evidence.basis());
        value.put("sources", evidence.sources());
        return value;
    }

    private Map<String, Object> thesisMap(InvestmentThesis thesis) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("thesis", thesis.type().name());
        value.put("status", thesis.status().name());
        value.put("confidence", thesis.confidence());
        value.put("conclusion", thesis.conclusion());
        value.put("supporting_signal_ids", thesis.supportingSignalIds());
        value.put("opposing_signal_ids", thesis.opposingSignalIds());
        value.put("unresolved_signal_ids", thesis.unresolvedSignalIds());
        return value;
    }

    private Map<String, Object> issueMap(UnresolvedIssue issue) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("issue_id", issue.id());
        value.put("thesis", issue.thesis().name());
        value.put("question", issue.question());
        value.put("required_evidence", issue.requiredEvidence());
        value.put("resolution_status", issue.resolutionStatus().name());
        value.put("capability_note", issue.capabilityNote());
        return value;
    }

    /** Copies one named factor, including its quality and basis metadata, into the summary. */
    private void putKeyFactor(
            Map<String, Object> target,
            String outputName,
            StockFactorProfile profile,
            FactorCategory category,
            String factorName) {
        profile.categories().get(category).factors().stream()
                .filter(value -> value.name().equals(factorName))
                .findFirst()
                .ifPresent(value -> target.put(outputName, factorMap(value)));
    }

    private Map<String, Object> factorMap(FactorValue factor) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("name", factor.name()); value.put("raw_value", factor.rawValue());
        value.put("normalized_value", factor.normalizedValue()); value.put("unit", factor.unit());
        value.put("direction", factor.direction().name()); value.put("as_of", factor.asOf().toString());
        value.put("report_period", factor.reportPeriod() == null ? null : factor.reportPeriod().toString());
        value.put("source", factor.sources()); value.put("formula", factor.formula());
        addValuationBasis(value, factor);
        value.put("quality", factor.quality().name());
        value.put("warnings", factor.warnings().stream().map(Enum::name).toList());
        value.put("limitation", factor.limitation()); return value;
    }

    private Map<String, Object> scoreMap(FactorScore score) {
        if (score == null) return Map.of();
        Map<String, Object> value = new java.util.LinkedHashMap<>(); value.put("score", score.score());
        value.put("coverage_pct", score.coveragePct()); value.put("quality", score.quality().name());
        value.put("contributing_factors", score.contributingFactors()); return value;
    }

    /** Adds machine-readable bases so trailing and forecast valuation fields cannot be conflated. */
    private void addValuationBasis(Map<String, Object> value, FactorValue factor) {
        if (factor.name().equals("pe_ttm") || factor.name().startsWith("pe_ttm_percentile_")) {
            value.put("valuation_basis", "PE_TTM");
            value.put("earnings_basis", "TRAILING_TWELVE_MONTHS");
        } else if (factor.name().equals("forward_pe")) {
            value.put("valuation_basis", "PE_FORWARD");
            value.put("earnings_basis", "CURRENT_FISCAL_YEAR_INSTITUTION_CONSENSUS_MEDIAN");
            value.put("forecast_year", factor.asOf().getYear());
        } else if (factor.name().equals("peer_pe_premium_pct")) {
            value.put("valuation_basis", "PE_TTM");
            value.put("comparison_basis", "TARGET_TTM_PE_VS_PURE_PEER_TTM_PE_MEDIAN");
        } else if (factor.name().equals("pb") || factor.name().startsWith("pb_percentile_")
                || factor.name().equals("peer_pb_premium_pct")) {
            value.put("valuation_basis", "PB_CURRENT");
        }
    }
}
