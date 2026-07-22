package com.stockmind.application.factor;

import com.stockmind.application.financial.CanonicalFinancialStatementPeriod;
import com.stockmind.application.financial.CanonicalFinancialValue;
import com.stockmind.application.financial.FinancialValueUnit;
import com.stockmind.application.financial.StatementScope;
import com.stockmind.application.research.AnalystForecastConsensus;
import com.stockmind.application.research.AnalystReport;
import com.stockmind.application.risk.SupplementalRiskSnapshot;
import com.stockmind.application.sector.SectorConstituentSet;
import com.stockmind.application.snapshot.FinancialSnapshot;
import com.stockmind.application.snapshot.IndustryPeerSnapshot;
import com.stockmind.application.snapshot.PointInTimeStockSnapshot;
import com.stockmind.domain.factor.FactorCategoryResult;
import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorDirection;
import com.stockmind.domain.factor.FactorQuality;
import com.stockmind.domain.factor.FactorValue;
import com.stockmind.domain.instrument.Exchange;
import com.stockmind.domain.instrument.Instrument;
import com.stockmind.domain.instrument.InstrumentType;
import com.stockmind.domain.market.AdjustmentMode;
import com.stockmind.domain.market.BarDataset;
import com.stockmind.domain.market.BarInterval;
import com.stockmind.domain.market.MarketBar;
import com.stockmind.domain.market.MarketPriceSnapshot;
import com.stockmind.domain.market.PriceSourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Regression coverage for fiscal-year forecasts and same-period growth semantics. */
public final class BusinessSemanticRegressionAcceptanceTest {
    public static void main(String[] args) {
        verifiesFiscalYearAlignment();
        verifiesSameReportPeriodGrowthComparison();
        verifiesNullNormalizedValueCannotEnterScoring();
    }

    private static void verifiesFiscalYearAlignment() {
        List<AnalystReport> reports = List.of(
                report("old-a", "A", LocalDate.of(2025, 10, 1), "4", "5", "6"),
                report("new-a", "A", LocalDate.of(2026, 4, 1), "6", "7", "8"),
                report("old-b", "B", LocalDate.of(2025, 9, 1), "5", "7", "9"));
        var consensus = new AnalystForecastConsensus();
        var year2026 = consensus.summarize(reports, LocalDate.of(2026, 7, 20), 2026);
        var year2027 = consensus.summarize(reports, LocalDate.of(2026, 7, 20), 2027);
        require(year2026.sampleCount() == 2
                        && year2026.median().compareTo(new BigDecimal("6.5")) == 0,
                "2025研报nextYear EPS与2026研报currentYear EPS未按2026财年对齐");
        require(year2027.sampleCount() == 2
                        && year2027.median().compareTo(new BigDecimal("8")) == 0,
                "下一财年EPS未按机构和预测年度去重");
    }

    private static void verifiesSameReportPeriodGrowthComparison() {
        LocalDate asOf = LocalDate.of(2026, 7, 20);
        CanonicalFinancialStatementPeriod q1_2026 = income(
                LocalDate.of(2026, 3, 31), "0.20", "0.20");
        CanonicalFinancialStatementPeriod q3_2025 = income(
                LocalDate.of(2025, 9, 30), "0.90", "0.90");
        CanonicalFinancialStatementPeriod q1_2025 = income(
                LocalDate.of(2025, 3, 31), "0.10", "0.10");
        FinancialSnapshot financials = new FinancialSnapshot(
                Map.of("lrb", List.of(q1_2026, q3_2025, q1_2025)), 0);
        PointInTimeStockSnapshot snapshot = snapshot(asOf, financials);
        FactorCategoryResult growth = new GrowthFactorCalculator().calculate(snapshot);
        FactorValue change = factor(growth, "comparable_growth_change_pct");
        require(change.rawValue().compareTo(new BigDecimal("10.00")) == 0,
                "2026Q1错误地与2025Q3而不是2025Q1比较");
        require(change.limitation().contains("2025-03-31"),
                "成长变化因子未披露实际可比报告期");
        require(growth.signals().contains("growth_state=ACCELERATING"),
                "同报告期同比改善未识别为加速");
    }

    private static void verifiesNullNormalizedValueCannotEnterScoring() {
        FactorValue invalid = new FactorValue(
                "pe_ttm_percentile_3y", null, null, "percentile",
                FactorDirection.LOWER_IS_BETTER, LocalDate.of(2026, 7, 21), null,
                List.of("fixture"), "fixture", FactorQuality.VALID, List.of(), "");
        FactorCategoryResult result = new FactorCategoryResult(
                FactorCategory.VALUATION, List.of(invalid), List.of(), List.of());
        var score = new FactorNormalizationService().score(result);
        require(score.score() == null && score.coveragePct().signum() == 0,
                "空规范值即使被上游误标VALID也不得进入评分");
    }

    private static AnalystReport report(
            String id, String institution, LocalDate date,
            String current, String next, String nextTwo) {
        return new AnalystReport(id, "603259", "研报", institution, date, "买入",
                new BigDecimal(current), new BigDecimal(next), new BigDecimal(nextTwo), "");
    }

    private static CanonicalFinancialStatementPeriod income(
            LocalDate period, String revenueYoy, String profitYoy) {
        LocalDate published = period.plusMonths(1);
        return new CanonicalFinancialStatementPeriod(
                period, published, published, StatementScope.CUMULATIVE_QUARTER,
                false, "lrb",
                Map.of(
                        "营业收入", value("营业收入", "1000", FinancialValueUnit.CNY),
                        "净利润", value("净利润", "300", FinancialValueUnit.CNY)),
                Map.of(
                        "营业收入", value("营业收入", revenueYoy, FinancialValueUnit.RATIO),
                        "净利润", value("净利润", profitYoy, FinancialValueUnit.RATIO)));
    }

    private static CanonicalFinancialValue value(
            String name, String value, FinancialValueUnit unit) {
        return new CanonicalFinancialValue(name, value, new BigDecimal(value), unit, unit.name());
    }

    private static PointInTimeStockSnapshot snapshot(
            LocalDate asOf, FinancialSnapshot financials) {
        Instrument instrument = new Instrument(
                "SH603259", "603259", Exchange.SH, InstrumentType.STOCK, "药明康德");
        MarketPriceSnapshot price = new MarketPriceSnapshot(
                "SH603259", "药明康德", asOf, asOf, PriceSourceType.REALTIME_QUOTE,
                BigDecimal.valueOf(100), null, null, null, null, null,
                BigDecimal.valueOf(20), BigDecimal.valueOf(4), "fixture", "quote", List.of());
        Instant close = asOf.atTime(15, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        MarketBar bar = new MarketBar(
                "SH603259", BarInterval.DAY_1, close.minusSeconds(19_800), close,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                BigDecimal.valueOf(100), BigDecimal.ONE, BigDecimal.ONE,
                AdjustmentMode.FORWARD);
        BarDataset bars = new BarDataset("bars", List.of(bar), "fixture", close, List.of());
        IndustryPeerSnapshot peers = new IndustryPeerSnapshot(
                asOf, true, true,
                new SectorConstituentSet("BK", "行业", List.of()), List.of(), List.of());
        return new PointInTimeStockSnapshot(
                "request", instrument, asOf, price, bars, bars, peers,
                SupplementalRiskSnapshot.unavailable(asOf, "fixture"), financials,
                List.of(), List.of(), 0, 0, Map.of());
    }

    private static FactorValue factor(FactorCategoryResult result, String name) {
        return result.factors().stream().filter(value -> value.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
