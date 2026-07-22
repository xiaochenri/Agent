package com.stockmind.application.research;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aligns rolling report fields to explicit fiscal years before calculating consensus. */
public final class AnalystForecastConsensus {
    private static final int SCALE = 8;

    /**
     * Keeps the latest positive forecast per institution for one fiscal year at one cutoff.
     * A report published in year Y contributes current/next/next-two EPS to Y/Y+1/Y+2.
     */
    public Map<String, BigDecimal> latestByInstitution(
            List<AnalystReport> reports, LocalDate cutoff, int forecastYear) {
        List<ForecastObservation> observations = new ArrayList<>();
        for (AnalystReport report : reports) {
            if (report.publishedDate() == null || report.publishedDate().isAfter(cutoff)
                    || report.institution() == null || report.institution().isBlank()) {
                continue;
            }
            int baseYear = report.forecastBaseYear();
            add(observations, report, baseYear, report.currentYearEps(), forecastYear);
            add(observations, report, baseYear + 1, report.nextYearEps(), forecastYear);
            add(observations, report, baseYear + 2, report.nextTwoYearEps(), forecastYear);
        }
        observations.sort(Comparator.comparing(ForecastObservation::publishedDate).reversed());
        Map<String, BigDecimal> latest = new LinkedHashMap<>();
        for (ForecastObservation observation : observations) {
            latest.putIfAbsent(observation.institution(), observation.eps());
        }
        return Map.copyOf(latest);
    }

    /** Calculates descriptive statistics after fiscal-year alignment and institution deduplication. */
    public ForecastSummary summarize(
            List<AnalystReport> reports, LocalDate cutoff, int forecastYear) {
        Map<String, BigDecimal> forecasts = latestByInstitution(reports, cutoff, forecastYear);
        List<BigDecimal> values = forecasts.values().stream().sorted().toList();
        if (values.isEmpty()) {
            return new ForecastSummary(forecastYear, 0, null, null, null, null, forecasts);
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
        int size = values.size();
        BigDecimal median = size % 2 == 1
                ? values.get(size / 2)
                : values.get(size / 2 - 1).add(values.get(size / 2))
                        .divide(BigDecimal.TWO, SCALE, RoundingMode.HALF_UP);
        return new ForecastSummary(
                forecastYear, size, median, mean, values.getFirst(), values.getLast(), forecasts);
    }

    private void add(
            List<ForecastObservation> observations,
            AnalystReport report,
            int valueYear,
            BigDecimal eps,
            int requestedYear) {
        if (valueYear == requestedYear && eps != null && eps.signum() > 0) {
            observations.add(new ForecastObservation(
                    report.institution(), report.publishedDate(), eps));
        }
    }

    private record ForecastObservation(
            String institution, LocalDate publishedDate, BigDecimal eps) {}

    /** Fiscal-year-specific institution consensus. */
    public record ForecastSummary(
            int forecastYear,
            int sampleCount,
            BigDecimal median,
            BigDecimal mean,
            BigDecimal minimum,
            BigDecimal maximum,
            Map<String, BigDecimal> byInstitution) {
        public ForecastSummary {
            byInstitution = Map.copyOf(byInstitution);
        }
    }
}
