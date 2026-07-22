package com.stockmind.application.factor;

import com.stockmind.application.snapshot.PointInTimeStockSnapshot;
import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorCategoryResult;

public interface FactorCalculator {
    /** Returns the single factor category produced by this calculator. */
    FactorCategory category();

    /**
     * Calculates deterministic factor values from one already time-filtered snapshot.
     * Implementations must not fetch newer data or turn missing values into negative signals.
     */
    FactorCategoryResult calculate(PointInTimeStockSnapshot snapshot);
}
