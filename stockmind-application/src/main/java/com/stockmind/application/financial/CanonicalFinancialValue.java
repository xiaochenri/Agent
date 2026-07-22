package com.stockmind.application.financial;

import java.math.BigDecimal;

/** A parsed financial value in the canonical unit used by factor calculators. */
public record CanonicalFinancialValue(
        String metric,
        String rawValue,
        BigDecimal value,
        FinancialValueUnit unit,
        String sourceUnit) {
}
