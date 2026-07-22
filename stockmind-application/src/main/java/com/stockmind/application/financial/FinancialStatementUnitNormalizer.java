package com.stockmind.application.financial;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts Sina statement strings into deterministic values and canonical units. */
public final class FinancialStatementUnitNormalizer {

    /** Converts provider units and percentage representations to the canonical factor units. */
    public CanonicalFinancialStatementPeriod normalize(FinancialStatementPeriod period) {
        Map<String, CanonicalFinancialValue> values = new LinkedHashMap<>();
        period.values().forEach((metric, raw) -> parse(metric, raw, false)
                .ifPresent(value -> values.put(metric, value)));
        Map<String, CanonicalFinancialValue> yoy = new LinkedHashMap<>();
        period.yearOverYearValues().forEach((metric, raw) -> parse(metric, raw, true)
                .ifPresent(value -> yoy.put(metric, value)));
        return new CanonicalFinancialStatementPeriod(period.reportPeriod(), period.initialPublishedDate(),
                period.latestUpdatedDate(), period.statementScope(), period.restated(), period.statementType(),
                values, yoy);
    }

    private java.util.Optional<CanonicalFinancialValue> parse(String metric, String raw, boolean yearOverYear) {
        if (metric == null || metric.isBlank() || raw == null || raw.isBlank()) return java.util.Optional.empty();
        String text = raw.trim().replace(",", "").replace("，", "").replace(" ", "");
        if (text.equals("-") || text.equals("--") || text.equals("N/A") || text.equalsIgnoreCase("null")) {
            return java.util.Optional.empty();
        }
        boolean explicitPercent = text.endsWith("%");
        text = text.replaceFirst("[%％]$", "").replaceFirst("元$", "");
        try {
            BigDecimal numeric = new BigDecimal(text);
            FinancialValueUnit unit = yearOverYear ? FinancialValueUnit.RATIO : classify(metric);
            String sourceUnit = sourceUnit(unit, yearOverYear, explicitPercent);
            // Sina item_tongbi is already a decimal ratio (0.085 means 8.5%). Only text
            // carrying an explicit percent sign, or non-YoY percentage metrics, needs scaling.
            if (explicitPercent || (!yearOverYear && isPercentMetric(metric))) {
                numeric = numeric.movePointLeft(2);
            }
            return java.util.Optional.of(new CanonicalFinancialValue(metric, raw, numeric, unit, sourceUnit));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private FinancialValueUnit classify(String metric) {
        if (metric.contains("每股")) return FinancialValueUnit.CNY_PER_SHARE;
        if (metric.contains("股本") || metric.contains("股数") || metric.contains("股份")) {
            return FinancialValueUnit.SHARES;
        }
        if (metric.contains("天数") || metric.endsWith("天")) return FinancialValueUnit.DAYS;
        if (isPercentMetric(metric) || metric.contains("倍") || metric.contains("周转率")) {
            return FinancialValueUnit.RATIO;
        }
        // The three admitted Sina statements expose monetary line items in CNY yuan.
        return FinancialValueUnit.CNY;
    }

    private boolean isPercentMetric(String metric) {
        return metric.contains("率") || metric.contains("比重") || metric.contains("占比");
    }

    private String sourceUnit(FinancialValueUnit unit, boolean yearOverYear, boolean explicitPercent) {
        if (yearOverYear || explicitPercent || unit == FinancialValueUnit.RATIO) return "percent_or_ratio_by_metric_contract";
        return switch (unit) {
            case CNY -> "CNY_yuan";
            case CNY_PER_SHARE -> "CNY_per_share";
            case SHARES -> "shares";
            case DAYS -> "days";
            default -> "unknown";
        };
    }
}
