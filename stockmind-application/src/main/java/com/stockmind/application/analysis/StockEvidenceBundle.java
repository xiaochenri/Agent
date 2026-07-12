package com.stockmind.application.analysis;

import java.util.List;
import java.util.Map;

/** Normalized evidence supplied to the final stock analysis tool. */
public record StockEvidenceBundle(
        String symbol,
        Map<String, Object> quote,
        Map<String, Object> technicalIndicators,
        Map<String, Object> marketBars,
        Map<String, Object> news,
        Map<String, Object> fundamentals,
        List<String> warnings) {
}
