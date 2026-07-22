package com.stockmind.application.factor;

import com.stockmind.application.financial.CanonicalFinancialStatementPeriod;
import com.stockmind.application.financial.StatementScope;
import com.stockmind.application.research.AnalystForecastConsensus;
import com.stockmind.application.snapshot.PointInTimeStockSnapshot;
import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorCategoryResult;
import com.stockmind.domain.factor.FactorDirection;
import com.stockmind.domain.factor.FactorQuality;
import com.stockmind.domain.factor.FactorValue;
import com.stockmind.domain.factor.FactorWarning;
import com.stockmind.domain.factor.GrowthState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Calculates reported and institution-forecast growth using comparable fiscal periods. */
public final class GrowthFactorCalculator implements FactorCalculator {
    /** {@inheritDoc} */
    public FactorCategory category() {
        return FactorCategory.GROWTH;
    }

    /** Annual and interim growth are kept separate so incompatible periods are never compared. */
    public FactorCategoryResult calculate(PointInTimeStockSnapshot snapshot) {
        List<CanonicalFinancialStatementPeriod> statements = snapshot.financials().statements()
                .getOrDefault("lrb", List.of());
        CanonicalFinancialStatementPeriod annual = statements.stream()
                .filter(value -> value.statementScope() == StatementScope.ANNUAL)
                .findFirst().orElse(null);
        CanonicalFinancialStatementPeriod interim = statements.stream()
                .filter(value -> value.statementScope() != StatementScope.ANNUAL)
                .findFirst().orElse(null);

        List<FactorValue> factors = new ArrayList<>();
        addPair(factors, "latest_annual", annual, snapshot);
        addPair(factors, "latest_quarter", interim, snapshot);

        CanonicalFinancialStatementPeriod latest = interim != null ? interim : annual;
        CanonicalFinancialStatementPeriod priorComparable = comparablePriorYear(statements, latest);
        BigDecimal currentGrowth = profitYoy(latest);
        BigDecimal priorGrowth = profitYoy(priorComparable);
        BigDecimal change = currentGrowth == null || priorGrowth == null
                ? null : currentGrowth.subtract(priorGrowth);
        factors.add(change == null
                ? FactorSupport.missing(
                        "comparable_growth_change_pct", "percent",
                        FactorDirection.HIGHER_IS_BETTER, snapshot,
                        "latest_yoy-prior_year_same_report_period_yoy",
                        "缺少上年同一报告期的同口径同比")
                : FactorSupport.value(
                        "comparable_growth_change_pct", FactorSupport.pct(change), change,
                        "percent", FactorDirection.HIGHER_IS_BETTER, snapshot,
                        latest.reportPeriod(), List.of("sina_financial"),
                        "latest_yoy-prior_year_same_report_period_yoy",
                        FactorQuality.VALID, List.of(),
                        "prior_comparable_period=" + priorComparable.reportPeriod()));
        GrowthState growthState = state(currentGrowth, priorGrowth);

        int currentFiscalYear = snapshot.requestedAsOf().getYear();
        AnalystForecastConsensus consensus = new AnalystForecastConsensus();
        var yearOne = consensus.summarize(
                snapshot.analystReports(), snapshot.requestedAsOf(), currentFiscalYear);
        var yearThree = consensus.summarize(
                snapshot.analystReports(), snapshot.requestedAsOf(), currentFiscalYear + 2);
        BigDecimal forecastCagr = null;
        if (yearOne.median() != null && yearThree.median() != null) {
            forecastCagr = BigDecimal.valueOf(Math.pow(
                    yearThree.median().divide(yearOne.median(), 12, RoundingMode.HALF_UP)
                            .doubleValue(),
                    0.5) - 1);
        }
        int forecastSample = Math.min(yearOne.sampleCount(), yearThree.sampleCount());
        factors.add(forecastCagr == null
                ? FactorSupport.missing(
                        "forecast_eps_cagr_3y_pct", "percent",
                        FactorDirection.HIGHER_IS_BETTER, snapshot,
                        "(year3_eps/year1_eps)^(1/2)-1", "预测样本不足")
                : FactorSupport.value(
                        "forecast_eps_cagr_3y_pct", FactorSupport.pct(forecastCagr),
                        forecastCagr, "percent", FactorDirection.HIGHER_IS_BETTER, snapshot,
                        null, List.of("eastmoney_research"),
                        "(fiscal_year_" + (currentFiscalYear + 2) + "_eps_median/fiscal_year_"
                                + currentFiscalYear + "_eps_median)^(1/2)-1",
                        forecastSample < 3 ? FactorQuality.LOW_SAMPLE : FactorQuality.VALID,
                        forecastSample < 3 ? List.of(FactorWarning.LOW_FORECAST_SAMPLE) : List.of(),
                        "forecast_years=" + currentFiscalYear + "," + (currentFiscalYear + 2)
                                + ",sample_count=" + forecastSample));
        String forecastState = forecastCagr == null
                ? "INSUFFICIENT_DATA"
                : forecastCagr.signum() < 0 ? "TURNING_NEGATIVE" : "STABLE_OR_GROWING";
        return FactorSupport.result(
                category(), factors,
                growthState == GrowthState.INSUFFICIENT_DATA
                        ? List.of(FactorWarning.INSUFFICIENT_DATA) : List.of(),
                List.of("growth_state=" + growthState, "forecast_growth_state=" + forecastState));
    }

    private CanonicalFinancialStatementPeriod comparablePriorYear(
            List<CanonicalFinancialStatementPeriod> statements,
            CanonicalFinancialStatementPeriod latest) {
        if (latest == null) return null;
        int priorYear = latest.reportPeriod().getYear() - 1;
        int month = latest.reportPeriod().getMonthValue();
        int day = latest.reportPeriod().getDayOfMonth();
        return statements.stream()
                .filter(value -> value.reportPeriod().getYear() == priorYear
                        && value.reportPeriod().getMonthValue() == month
                        && value.reportPeriod().getDayOfMonth() == day
                        && value.statementScope() == latest.statementScope())
                .findFirst().orElse(null);
    }

    private void addPair(
            List<FactorValue> factors,
            String prefix,
            CanonicalFinancialStatementPeriod period,
            PointInTimeStockSnapshot snapshot) {
        add(factors, prefix + "_revenue_yoy_pct", revenueYoy(period), period, snapshot);
        add(factors, prefix + "_net_profit_yoy_pct", profitYoy(period), period, snapshot);
    }

    private void add(
            List<FactorValue> factors,
            String name,
            BigDecimal value,
            CanonicalFinancialStatementPeriod period,
            PointInTimeStockSnapshot snapshot) {
        factors.add(value == null
                ? FactorSupport.missing(
                        name, "percent", FactorDirection.HIGHER_IS_BETTER, snapshot,
                        "provider_yoy", "缺失同口径同比")
                : FactorSupport.value(
                        name, FactorSupport.pct(value), value, "percent",
                        FactorDirection.HIGHER_IS_BETTER, snapshot, period.reportPeriod(),
                        List.of("sina_financial"), "provider_yoy_decimal_ratio",
                        FactorQuality.VALID, List.of(), ""));
    }

    private BigDecimal revenueYoy(CanonicalFinancialStatementPeriod period) {
        return FactorSupport.yoy(period, "营业总收入", "营业收入");
    }

    private BigDecimal profitYoy(CanonicalFinancialStatementPeriod period) {
        return FactorSupport.yoy(period,
                "归属于母公司所有者的净利润",
                "归属于母公司股东的净利润",
                "归属于上市公司股东的净利润",
                "净利润");
    }

    private GrowthState state(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null) return GrowthState.INSUFFICIENT_DATA;
        if (current.signum() < 0 && previous.signum() >= 0) return GrowthState.TURNING_NEGATIVE;
        if (current.signum() >= 0 && previous.signum() < 0) return GrowthState.RECOVERING;
        BigDecimal difference = current.subtract(previous);
        if (difference.compareTo(new BigDecimal("0.05")) > 0) return GrowthState.ACCELERATING;
        if (difference.compareTo(new BigDecimal("-0.05")) < 0) return GrowthState.DECELERATING;
        return GrowthState.STABLE;
    }
}
