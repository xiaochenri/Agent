package com.stockmind.domain.factor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record StockFactorProfile(
        String requestId, String symbol, String name, LocalDate asOf, LocalDate effectiveTradeDate,
        String policyVersion, InvestmentStyle style, FactorCoverage coverage, BigDecimal overallScore,
        Map<FactorCategory, FactorCategoryResult> categories, Map<FactorCategory, FactorScore> categoryScores,
        InvestmentAssessmentGuardrails assessmentGuardrails,
        List<String> positiveSignals, List<String> negativeSignals, List<FactorWarning> warnings,
        List<String> limitations, List<String> sources) {
    public StockFactorProfile {
        categories = Map.copyOf(categories); categoryScores = Map.copyOf(categoryScores);
        positiveSignals = List.copyOf(positiveSignals); negativeSignals = List.copyOf(negativeSignals);
        warnings = List.copyOf(warnings); limitations = List.copyOf(limitations); sources = List.copyOf(sources);
    }
}
