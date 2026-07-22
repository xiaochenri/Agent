package com.stockmind.application.factor;

import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorCategoryResult;
import com.stockmind.domain.factor.FactorCoverage;
import com.stockmind.domain.factor.FactorDirection;
import com.stockmind.domain.factor.FactorQuality;
import com.stockmind.domain.factor.FactorScore;
import com.stockmind.domain.factor.FactorValue;
import com.stockmind.domain.factor.InvestmentConclusionStrength;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Separates overall conclusion strength from permissions for individual strong claims. */
public final class InvestmentAssessmentPolicyAcceptanceTest {
    public static void main(String[] args) {
        var policy = new InvestmentAssessmentPolicy();
        Map<FactorCategory, FactorCategoryResult> unresolved = categories(
                value("latest_annual_revenue_yoy_pct", "15.84", "0.1584", LocalDate.of(2025, 12, 31)),
                value("latest_annual_net_profit_yoy_pct", "102.65", "1.0265", LocalDate.of(2025, 12, 31)),
                missing("non_recurring_profit_ratio_pct"),
                value("cash_to_net_profit_ratio", "0.90", "0.90", LocalDate.of(2026, 3, 31)),
                value("roe_pct", "6.00", "0.06", LocalDate.of(2026, 3, 31)));
        var tentative = policy.evaluate(coverage("89.23"), BigDecimal.valueOf(57.9), unresolved, scores());
        require(tentative.directionalConclusionAllowed(), "覆盖充分时证据缺口不应锁死条件性方向判断");
        require(tentative.maximumConclusionStrength() == InvestmentConclusionStrength.TENTATIVE,
                "关键证据缺失时结论强度必须降为TENTATIVE");
        require(tentative.requiredGaps().contains("EARNINGS_ATTRIBUTION_UNRESOLVED"),
                "未暴露盈利归因缺口");
        require(!tentative.claimPermissions().get("high_earnings_quality")
                        && !tentative.claimPermissions().get("high_roe")
                        && !tentative.claimPermissions().get("clearly_undervalued"),
                "强盈利质量、高ROE或明显低估声明未被禁止");

        var insufficient = policy.evaluate(coverage("55"), BigDecimal.valueOf(57.9), unresolved, scores());
        require(!insufficient.directionalConclusionAllowed()
                        && insufficient.maximumConclusionStrength() == InvestmentConclusionStrength.INSUFFICIENT,
                "覆盖率不足时仍应禁止综合方向判断");

        Map<FactorCategory, FactorCategoryResult> resolved = categories(
                value("latest_annual_revenue_yoy_pct", "15", "0.15", LocalDate.of(2025, 12, 31)),
                value("latest_annual_net_profit_yoy_pct", "20", "0.20", LocalDate.of(2025, 12, 31)),
                value("non_recurring_profit_ratio_pct", "10", "0.10", LocalDate.of(2025, 12, 31)),
                value("cash_to_net_profit_ratio", "0.95", "0.95", LocalDate.of(2025, 12, 31)),
                value("roe_pct", "20", "0.20", LocalDate.of(2025, 12, 31)));
        var partialCoverage = policy.evaluate(coverage("70"), BigDecimal.valueOf(57.9), resolved, scores());
        require(partialCoverage.directionalConclusionAllowed()
                        && partialCoverage.maximumConclusionStrength() == InvestmentConclusionStrength.TENTATIVE,
                "60%-80%覆盖率应允许暂定、条件性的方向判断");
        var allowed = policy.evaluate(coverage("90"), BigDecimal.valueOf(70), resolved, scores());
        require(allowed.directionalConclusionAllowed(), "关键盈利证据完整时应允许平衡方向判断");
        require(allowed.claimPermissions().get("high_earnings_quality")
                        && allowed.claimPermissions().get("high_roe"),
                "完整年度质量证据未授权相应声明");
        require(!allowed.claimPermissions().get("clearly_undervalued"),
                "宽泛行业同行不得授权明显低估声明");
    }

    private static Map<FactorCategory, FactorCategoryResult> categories(
            FactorValue annualRevenue, FactorValue annualProfit, FactorValue nonRecurring,
            FactorValue cashConversion, FactorValue roe) {
        Map<FactorCategory, FactorCategoryResult> result = new EnumMap<>(FactorCategory.class);
        result.put(FactorCategory.GROWTH, category(FactorCategory.GROWTH, annualRevenue, annualProfit));
        result.put(FactorCategory.QUALITY, category(FactorCategory.QUALITY, nonRecurring, cashConversion, roe));
        result.put(FactorCategory.VALUATION, category(FactorCategory.VALUATION,
                value("pe_ttm_percentile_3y", "25", "0.25", LocalDate.of(2026, 7, 21)),
                value("peer_pe_premium_pct", "-60", "-0.60", LocalDate.of(2026, 7, 21))));
        return result;
    }

    private static FactorCategoryResult category(FactorCategory category, FactorValue... values) {
        return new FactorCategoryResult(category, List.of(values), List.of(), List.of());
    }

    private static FactorCoverage coverage(String value) {
        return new FactorCoverage(new BigDecimal(value), FactorQuality.VALID, Map.of());
    }

    private static Map<FactorCategory, FactorScore> scores() {
        return Map.of(FactorCategory.QUALITY, new FactorScore(FactorCategory.QUALITY,
                BigDecimal.valueOf(70), BigDecimal.valueOf(100), FactorQuality.VALID, List.of("quality")));
    }

    private static FactorValue value(String name, String raw, String normalized, LocalDate period) {
        return new FactorValue(name, new BigDecimal(raw), new BigDecimal(normalized), "fixture",
                FactorDirection.NEUTRAL, LocalDate.of(2026, 7, 21), period, List.of("fixture"),
                "fixture", FactorQuality.VALID, List.of(), "");
    }

    private static FactorValue missing(String name) {
        return new FactorValue(name, null, null, "fixture", FactorDirection.NEUTRAL,
                LocalDate.of(2026, 7, 21), null, List.of(), "fixture",
                FactorQuality.MISSING, List.of(), "missing");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
