package com.stockmind.application.snapshot;

import com.stockmind.application.financial.CanonicalFinancialStatementPeriod;
import java.util.List;
import java.util.Map;

public record FinancialSnapshot(
        Map<String, List<CanonicalFinancialStatementPeriod>> statements,
        int filteredFutureRecords) {

    public FinancialSnapshot {
        statements = statements == null ? Map.of() : Map.copyOf(statements);
    }
}
