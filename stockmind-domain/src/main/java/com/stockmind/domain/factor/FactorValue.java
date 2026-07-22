package com.stockmind.domain.factor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FactorValue(
        String name,
        BigDecimal rawValue,
        BigDecimal normalizedValue,
        String unit,
        FactorDirection direction,
        LocalDate asOf,
        LocalDate reportPeriod,
        List<String> sources,
        String formula,
        FactorQuality quality,
        List<FactorWarning> warnings,
        String limitation) {
    public FactorValue {
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        limitation = limitation == null ? "" : limitation;
    }
    /** A factor can enter scoring only when both its quality and normalized value are usable. */
    public boolean usable() {
        return normalizedValue != null
                && (quality == FactorQuality.VALID || quality == FactorQuality.PARTIAL);
    }
}
