package com.stockmind.domain.factor;

import java.util.List;

public record FactorCategoryResult(FactorCategory category, List<FactorValue> factors,
                                   List<FactorWarning> warnings, List<String> signals) {
    public FactorCategoryResult {
        factors = List.copyOf(factors); warnings = List.copyOf(warnings); signals = List.copyOf(signals);
    }
}
