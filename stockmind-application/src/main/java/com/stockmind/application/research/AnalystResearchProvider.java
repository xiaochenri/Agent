package com.stockmind.application.research;

import java.util.List;

public interface AnalystResearchProvider {
    List<AnalystReport> loadCompanyReports(String symbol, int limit);
}
