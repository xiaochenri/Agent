package com.stockmind.application.financial;

import java.util.List;

public interface FinancialReportProvider {
    List<FinancialStatementPeriod> load(String symbol, String statementType, int periods);
}
