package com.stockmind.application.financial;

import java.time.LocalDate;
import java.util.Map;

/** One reporting period from a structured financial statement. */
public record FinancialStatementPeriod(
        LocalDate reportPeriod,
        LocalDate publishedDate,
        LocalDate initialPublishedDate,
        LocalDate latestUpdatedDate,
        StatementScope statementScope,
        boolean restated,
        String statementType,
        Map<String, String> values,
        Map<String, String> yearOverYearValues) {

    public FinancialStatementPeriod {
        if (reportPeriod == null) throw new IllegalArgumentException("reportPeriod不能为空");
        initialPublishedDate = initialPublishedDate == null ? publishedDate : initialPublishedDate;
        latestUpdatedDate = latestUpdatedDate == null ? publishedDate : latestUpdatedDate;
        statementScope = statementScope == null ? StatementScope.UNKNOWN : statementScope;
        values = values == null ? Map.of() : Map.copyOf(values);
        yearOverYearValues = yearOverYearValues == null ? Map.of() : Map.copyOf(yearOverYearValues);
    }

    /** Compatibility constructor for providers that expose only one disclosure timestamp. */
    public FinancialStatementPeriod(
            LocalDate reportPeriod,
            LocalDate publishedDate,
            String statementType,
            Map<String, String> values,
            Map<String, String> yearOverYearValues) {
        this(reportPeriod, publishedDate, publishedDate, publishedDate,
                StatementScope.inferFromReportPeriod(reportPeriod), false,
                statementType, values, yearOverYearValues);
    }
}
