package com.stockmind.domain.factor;

import java.math.BigDecimal;
import java.util.List;

public record FactorScore(FactorCategory category, BigDecimal score, BigDecimal coveragePct,
                          FactorQuality quality, List<String> contributingFactors) {
    public FactorScore { contributingFactors = List.copyOf(contributingFactors); }
}
