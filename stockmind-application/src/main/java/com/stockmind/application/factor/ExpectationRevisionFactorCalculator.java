package com.stockmind.application.factor;

import com.stockmind.application.financial.StatementScope;
import com.stockmind.application.research.AnalystForecastConsensus;
import com.stockmind.application.research.AnalystReport;
import com.stockmind.application.snapshot.PointInTimeStockSnapshot;
import com.stockmind.domain.factor.ExpectationRevisionState;
import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorCategoryResult;
import com.stockmind.domain.factor.FactorDirection;
import com.stockmind.domain.factor.FactorQuality;
import com.stockmind.domain.factor.FactorValue;
import com.stockmind.domain.factor.FactorWarning;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Tracks fiscal-year-aligned institution EPS revisions without treating them as facts. */
public final class ExpectationRevisionFactorCalculator implements FactorCalculator {
    private final AnalystForecastConsensus consensus = new AnalystForecastConsensus();

    /** {@inheritDoc} */
    public FactorCategory category() {
        return FactorCategory.EXPECTATION_REVISION;
    }

    /** Keeps the latest forecast per institution and target fiscal year at each cutoff. */
    public FactorCategoryResult calculate(PointInTimeStockSnapshot snapshot) {
        int forecastYear = snapshot.requestedAsOf().getYear();
        Map<String, BigDecimal> current = forecasts(snapshot, snapshot.requestedAsOf(), forecastYear);
        Map<String, BigDecimal> days30 = forecasts(
                snapshot, snapshot.requestedAsOf().minusDays(30), forecastYear);
        Map<String, BigDecimal> days90 = forecasts(
                snapshot, snapshot.requestedAsOf().minusDays(90), forecastYear);
        Map<String, BigDecimal> days180 = forecasts(
                snapshot, snapshot.requestedAsOf().minusDays(180), forecastYear);
        BigDecimal currentMedian = FactorSupport.median(List.copyOf(current.values()));
        BigDecimal median30 = FactorSupport.median(List.copyOf(days30.values()));
        BigDecimal median90 = FactorSupport.median(List.copyOf(days90.values()));
        BigDecimal median180 = FactorSupport.median(List.copyOf(days180.values()));
        BigDecimal revision30 = revision(currentMedian, median30);
        BigDecimal revision90 = revision(currentMedian, median90);
        BigDecimal revision180 = revision(currentMedian, median180);
        BigDecimal dispersion = dispersion(List.copyOf(current.values()));

        int upgrades = 0;
        int downgrades = 0;
        for (var entry : current.entrySet()) {
            BigDecimal old = days90.get(entry.getKey());
            if (old == null) continue;
            int comparison = entry.getValue().compareTo(old);
            if (comparison > 0) upgrades++;
            else if (comparison < 0) downgrades++;
        }

        FactorQuality quality = current.size() < 3
                ? FactorQuality.LOW_SAMPLE : FactorQuality.VALID;
        List<FactorValue> factors = new ArrayList<>();
        add(factors, "current_year_eps_median", currentMedian, "CNY_per_share",
                FactorDirection.HIGHER_IS_BETTER, snapshot, quality,
                "median(latest fiscal_year_" + forecastYear + " EPS by institution)", forecastYear);
        for (Window window : List.of(
                new Window(30, median30, revision30),
                new Window(90, median90, revision90),
                new Window(180, median180, revision180))) {
            add(factors, "eps_median_" + window.days() + "d_ago", window.median(),
                    "CNY_per_share", FactorDirection.NEUTRAL, snapshot, quality,
                    "median(latest fiscal_year_" + forecastYear + " EPS at cutoff)", forecastYear);
            add(factors, "eps_revision_" + window.days() + "d_pct", window.revision(),
                    "percent", FactorDirection.HIGHER_IS_BETTER, snapshot, quality,
                    "current fiscal-year EPS / historical fiscal-year EPS - 1", forecastYear);
        }
        add(factors, "forecast_dispersion", dispersion, "ratio",
                FactorDirection.LOWER_IS_BETTER, snapshot, quality,
                "stddev(fiscal-year EPS) / abs(mean(fiscal-year EPS))", forecastYear);
        add(factors, "upgrade_institution_count", BigDecimal.valueOf(upgrades), "count",
                FactorDirection.HIGHER_IS_BETTER, snapshot, quality,
                "count(current fiscal-year EPS > 90d fiscal-year EPS)", forecastYear);
        add(factors, "downgrade_institution_count", BigDecimal.valueOf(downgrades), "count",
                FactorDirection.LOWER_IS_BETTER, snapshot, quality,
                "count(current fiscal-year EPS < 90d fiscal-year EPS)", forecastYear);
        add(factors, "effective_institution_count", BigDecimal.valueOf(current.size()), "count",
                FactorDirection.HIGHER_IS_BETTER, snapshot, quality,
                "distinct institutions with fiscal-year-aligned EPS", forecastYear);
        addSurprise(factors, snapshot);

        ExpectationRevisionState state = state(revision30, revision90, current.size());
        Collection<AnalystReport> latestReports = latestReports(
                snapshot.analystReports(), snapshot.requestedAsOf()).values();
        return FactorSupport.result(
                category(), factors,
                quality == FactorQuality.LOW_SAMPLE
                        ? List.of(FactorWarning.LOW_FORECAST_SAMPLE) : List.of(),
                List.of("expectation_revision_state=" + state,
                        "rating_distribution=" + ratings(latestReports)));
    }

    private Map<String, BigDecimal> forecasts(
            PointInTimeStockSnapshot snapshot, LocalDate cutoff, int forecastYear) {
        return consensus.latestByInstitution(snapshot.analystReports(), cutoff, forecastYear);
    }

    private void addSurprise(List<FactorValue> factors, PointInTimeStockSnapshot snapshot) {
        var annual = snapshot.financials().statements().getOrDefault("lrb", List.of()).stream()
                .filter(value -> value.statementScope() == StatementScope.ANNUAL)
                .findFirst().orElse(null);
        if (annual == null) {
            add(factors, "annual_eps_surprise_pct", null, "percent",
                    FactorDirection.HIGHER_IS_BETTER, snapshot, FactorQuality.MISSING,
                    "actual_annual_eps/pre_release_consensus_eps-1", null);
            return;
        }
        BigDecimal actual = FactorSupport.metric(annual, "基本每股收益", "每股收益");
        var forecast = consensus.summarize(
                snapshot.analystReports(), annual.initialPublishedDate(),
                annual.reportPeriod().getYear());
        BigDecimal surprise = revision(actual, forecast.median());
        add(factors, "annual_eps_surprise_pct", surprise, "percent",
                FactorDirection.HIGHER_IS_BETTER, snapshot,
                forecast.sampleCount() < 3 ? FactorQuality.LOW_SAMPLE : FactorQuality.VALID,
                "actual_annual_eps/pre_release_fiscal_year_consensus_eps-1",
                annual.reportPeriod().getYear());
    }

    private Map<String, AnalystReport> latestReports(
            List<AnalystReport> reports, LocalDate cutoff) {
        return reports.stream()
                .filter(value -> value.publishedDate() != null
                        && !value.publishedDate().isAfter(cutoff)
                        && value.institution() != null && !value.institution().isBlank())
                .sorted(Comparator.comparing(AnalystReport::publishedDate).reversed())
                .collect(Collectors.toMap(
                        AnalystReport::institution, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
    }

    private BigDecimal revision(BigDecimal current, BigDecimal previous) {
        BigDecimal ratio = FactorSupport.div(current, previous);
        return ratio == null ? null : ratio.subtract(BigDecimal.ONE);
    }

    private BigDecimal dispersion(List<BigDecimal> values) {
        List<BigDecimal> usable = values.stream().filter(Objects::nonNull).toList();
        if (usable.size() < 2) return null;
        double mean = usable.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        if (mean == 0) return null;
        double sum = usable.stream()
                .mapToDouble(value -> Math.pow(value.doubleValue() - mean, 2)).sum();
        return BigDecimal.valueOf(Math.sqrt(sum / usable.size()) / Math.abs(mean));
    }

    private void add(
            List<FactorValue> factors,
            String name,
            BigDecimal value,
            String unit,
            FactorDirection direction,
            PointInTimeStockSnapshot snapshot,
            FactorQuality quality,
            String formula,
            Integer forecastYear) {
        BigDecimal raw = "percent".equals(unit) ? FactorSupport.pct(value) : value;
        FactorQuality effectiveQuality = value == null ? FactorQuality.MISSING : quality;
        factors.add(FactorSupport.value(
                name, raw, value, unit, direction, snapshot, null,
                List.of("eastmoney_research"), formula, effectiveQuality,
                quality == FactorQuality.LOW_SAMPLE
                        ? List.of(FactorWarning.LOW_FORECAST_SAMPLE) : List.of(),
                quality == FactorQuality.LOW_SAMPLE
                        ? "有效样本少于3，不生成方向性评分"
                        : forecastYear == null ? "" : "forecast_year=" + forecastYear));
    }

    private ExpectationRevisionState state(BigDecimal days30, BigDecimal days90, int samples) {
        if (samples < 3) return ExpectationRevisionState.LOW_SAMPLE;
        BigDecimal revision = days30 != null ? days30 : days90;
        if (revision == null) return ExpectationRevisionState.STABLE;
        if (revision.compareTo(new BigDecimal("0.10")) >= 0) {
            return ExpectationRevisionState.STRONGLY_UPGRADED;
        }
        if (revision.compareTo(new BigDecimal("0.03")) >= 0) {
            return ExpectationRevisionState.UPGRADED;
        }
        if (revision.compareTo(new BigDecimal("-0.10")) <= 0) {
            return ExpectationRevisionState.STRONGLY_DOWNGRADED;
        }
        if (revision.compareTo(new BigDecimal("-0.03")) <= 0) {
            return ExpectationRevisionState.DOWNGRADED;
        }
        return ExpectationRevisionState.STABLE;
    }

    private Map<String, Long> ratings(Collection<AnalystReport> reports) {
        return reports.stream()
                .filter(value -> value.rating() != null && !value.rating().isBlank())
                .collect(Collectors.groupingBy(
                        AnalystReport::rating, LinkedHashMap::new, Collectors.counting()));
    }

    private record Window(int days, BigDecimal median, BigDecimal revision) {}
}
