package com.stockmind.application.factor;

import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorCategoryResult;
import com.stockmind.domain.factor.FactorCoverage;
import com.stockmind.domain.factor.FactorScore;
import com.stockmind.domain.factor.FactorValue;
import com.stockmind.domain.factor.InvestmentAssessmentGuardrails;
import com.stockmind.domain.factor.InvestmentConclusionStrength;
import java.math.BigDecimal;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts factor availability and comparability into deterministic final-answer permissions. */
public final class InvestmentAssessmentPolicy {
    private static final BigDecimal NORMAL_COVERAGE = BigDecimal.valueOf(80);
    private static final BigDecimal MIN_DIRECTIONAL_COVERAGE = BigDecimal.valueOf(60);
    private static final BigDecimal MIN_HIGH_QUALITY_SCORE = BigDecimal.valueOf(65);
    private static final BigDecimal MAX_NON_RECURRING_RATIO_PCT = BigDecimal.valueOf(20);
    private static final BigDecimal MIN_CASH_CONVERSION = BigDecimal.valueOf(0.8);
    private static final BigDecimal ABNORMAL_GROWTH_GAP_PCT = BigDecimal.valueOf(30);
    private static final BigDecimal LOW_HISTORICAL_PE_PERCENTILE = BigDecimal.valueOf(30);
    private static final BigDecimal MIN_HIGH_ANNUAL_ROE_PCT = BigDecimal.valueOf(15);

    /**
     * Determines which strong financial claims are supported. Industry-board peers are treated as
     * broad context, so they cannot by themselves authorize a clear-undervaluation conclusion.
     */
    public InvestmentAssessmentGuardrails evaluate(
            FactorCoverage coverage,
            BigDecimal overallScore,
            Map<FactorCategory, FactorCategoryResult> categories,
            Map<FactorCategory, FactorScore> categoryScores) {
        FactorValue annualRevenueGrowth = factor(categories, FactorCategory.GROWTH,
                "latest_annual_revenue_yoy_pct");
        FactorValue annualProfitGrowth = factor(categories, FactorCategory.GROWTH,
                "latest_annual_net_profit_yoy_pct");
        FactorValue nonRecurring = factor(categories, FactorCategory.QUALITY,
                "non_recurring_profit_ratio_pct");
        FactorValue cashConversion = factor(categories, FactorCategory.QUALITY,
                "cash_to_net_profit_ratio");
        FactorValue roe = factor(categories, FactorCategory.QUALITY, "roe_pct");
        FactorValue historicalPe = factor(categories, FactorCategory.VALUATION,
                "pe_ttm_percentile_3y");
        FactorValue peerPe = factor(categories, FactorCategory.VALUATION, "peer_pe_premium_pct");

        boolean abnormalGrowthGap = usable(annualRevenueGrowth) && usable(annualProfitGrowth)
                && annualProfitGrowth.rawValue().subtract(annualRevenueGrowth.rawValue()).abs()
                .compareTo(ABNORMAL_GROWTH_GAP_PCT) >= 0;
        boolean earningsAttributionResolved = usable(nonRecurring);
        boolean criticalEarningsGap = abnormalGrowthGap && !earningsAttributionResolved;
        FactorScore qualityScore = categoryScores.get(FactorCategory.QUALITY);
        boolean highEarningsQuality = earningsAttributionResolved
                && nonRecurring.rawValue().abs().compareTo(MAX_NON_RECURRING_RATIO_PCT) <= 0
                && usable(cashConversion)
                && cashConversion.rawValue().compareTo(MIN_CASH_CONVERSION) >= 0
                && qualityScore != null && qualityScore.score() != null
                && qualityScore.score().compareTo(MIN_HIGH_QUALITY_SCORE) >= 0;
        boolean annualRoe = usable(roe) && roe.reportPeriod() != null
                && roe.reportPeriod().getMonth() == Month.DECEMBER
                && roe.reportPeriod().getDayOfMonth() == 31;
        boolean highRoe = annualRoe && roe.rawValue().compareTo(MIN_HIGH_ANNUAL_ROE_PCT) >= 0;
        boolean historicalValuationLow = usable(historicalPe)
                && historicalPe.rawValue().compareTo(LOW_HISTORICAL_PE_PERCENTILE) <= 0;
        boolean broadIndustryPeerAvailable = usable(peerPe);

        List<String> gaps = new ArrayList<>();
        if (criticalEarningsGap) gaps.add("EARNINGS_ATTRIBUTION_UNRESOLVED");
        if (broadIndustryPeerAvailable) gaps.add("COMPARABLE_PEER_SET_NOT_VALIDATED");
        if (!annualRoe) gaps.add("ANNUAL_ROE_EVIDENCE_UNAVAILABLE");
        gaps.add("RECENT_EVENT_IMPACT_NOT_OFFICIALLY_VERIFIED");

        boolean normalCoverage = coverage != null && coverage.coveragePct() != null
                && coverage.coveragePct().compareTo(NORMAL_COVERAGE) >= 0;
        boolean minimumCoverage = coverage != null && coverage.coveragePct() != null
                && coverage.coveragePct().compareTo(MIN_DIRECTIONAL_COVERAGE) >= 0;
        // Evidence gaps and partial coverage limit conclusion strength and individual claims. They
        // should not turn an otherwise usable profile into a dead end.
        boolean directionalAllowed = minimumCoverage && overallScore != null;
        InvestmentConclusionStrength strength;
        String readiness;
        if (!minimumCoverage || overallScore == null) {
            strength = InvestmentConclusionStrength.INSUFFICIENT;
            readiness = "INSUFFICIENT";
            directionalAllowed = false;
        } else if (!normalCoverage || criticalEarningsGap) {
            strength = InvestmentConclusionStrength.TENTATIVE;
            readiness = "PARTIAL";
        } else {
            strength = InvestmentConclusionStrength.NORMAL;
            readiness = "READY";
        }

        Map<String, Boolean> permissions = new LinkedHashMap<>();
        permissions.put("directional_investment_conclusion", directionalAllowed);
        permissions.put("high_earnings_quality", highEarningsQuality);
        // A low self-history percentile can support "historically low", but without a validated
        // business-comparable peer set it cannot support the stronger "clearly undervalued" claim.
        permissions.put("historically_low_valuation", historicalValuationLow);
        permissions.put("clearly_undervalued", false);
        permissions.put("high_roe", highRoe);
        permissions.put("broad_industry_peer_supports_clear_undervaluation", false);
        permissions.put("institution_opinion_as_fact", false);
        permissions.put("price_reaction_supports_intrinsic_value", false);
        permissions.put("geopolitical_risk_resolved", false);

        return new InvestmentAssessmentGuardrails(readiness, strength, directionalAllowed,
                permissions, gaps.stream().distinct().toList());
    }

    private FactorValue factor(
            Map<FactorCategory, FactorCategoryResult> categories,
            FactorCategory category,
            String name) {
        FactorCategoryResult result = categories.get(category);
        if (result == null) return null;
        return result.factors().stream().filter(value -> name.equals(value.name())).findFirst().orElse(null);
    }

    private boolean usable(FactorValue value) {
        return value != null && value.usable() && value.rawValue() != null;
    }
}
