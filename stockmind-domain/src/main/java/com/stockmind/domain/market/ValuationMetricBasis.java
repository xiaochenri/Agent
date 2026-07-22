package com.stockmind.domain.market;

/** Prevents accidental comparison of forward and trailing valuation multiples. */
public enum ValuationMetricBasis {
    PE_TTM,
    PE_FORWARD,
    PB_CURRENT;

    public boolean isComparableWith(ValuationMetricBasis other) {
        return this == other;
    }
}
