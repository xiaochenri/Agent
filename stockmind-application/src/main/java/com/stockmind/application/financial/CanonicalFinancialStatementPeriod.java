package com.stockmind.application.financial;

import java.time.LocalDate;
import java.util.Map;

/** Point-in-time financial statement whose numeric fields have explicit units. */
public record CanonicalFinancialStatementPeriod(
        LocalDate reportPeriod,
        LocalDate initialPublishedDate,
        LocalDate latestUpdatedDate,
        StatementScope statementScope,
        boolean restated,
        String statementType,
        Map<String, CanonicalFinancialValue> values,
        Map<String, CanonicalFinancialValue> yearOverYearValues) {

    public CanonicalFinancialStatementPeriod {
        values = values == null ? Map.of() : Map.copyOf(values);
        yearOverYearValues = yearOverYearValues == null ? Map.of() : Map.copyOf(yearOverYearValues);
    }
}
