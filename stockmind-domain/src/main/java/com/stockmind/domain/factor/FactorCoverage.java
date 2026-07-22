package com.stockmind.domain.factor;

import java.math.BigDecimal;
import java.util.Map;

public record FactorCoverage(BigDecimal coveragePct, FactorQuality quality,
                             Map<FactorCategory, BigDecimal> effectiveWeights) {
    public FactorCoverage { effectiveWeights = Map.copyOf(effectiveWeights); }
}
