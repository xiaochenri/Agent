package com.stockmind.application.research;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One broker research record with normalized annual EPS forecasts. */
public record AnalystReport(
        String id,
        String symbol,
        String title,
        String institution,
        LocalDate publishedDate,
        String rating,
        BigDecimal currentYearEps,
        BigDecimal nextYearEps,
        BigDecimal nextTwoYearEps,
        String pdfUrl) {

    /** Forecast year represented by currentYearEps according to the provider contract. */
    public int forecastBaseYear() {
        return publishedDate.getYear();
    }
}
