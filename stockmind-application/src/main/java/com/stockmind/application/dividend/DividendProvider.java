package com.stockmind.application.dividend;

import java.util.List;

public interface DividendProvider {
    List<DividendDistribution> loadHistory(String symbol, int limit);
}
