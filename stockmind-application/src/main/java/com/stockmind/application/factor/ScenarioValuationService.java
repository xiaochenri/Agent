package com.stockmind.application.factor;

import com.stockmind.application.research.AnalystForecastConsensus;
import com.stockmind.application.snapshot.PointInTimeStockSnapshot;
import com.stockmind.application.snapshot.PointInTimeStockSnapshotService;
import com.stockmind.domain.factor.ScenarioValuation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds auditable next-fiscal-year EPS x forward-PE scenarios. */
public final class ScenarioValuationService {
    private static final String NEXT_YEAR_EPS_BASIS =
            "NEXT_FISCAL_YEAR_INSTITUTION_CONSENSUS_MEDIAN";
    private static final String USER_FORWARD_PE_BASIS = "USER_SPECIFIED_FORWARD_PE";
    private static final String SENSITIVITY_FORWARD_PE_BASIS =
            "UNSPECIFIED_FORWARD_PE_SENSITIVITY_ONLY";

    private final PointInTimeStockSnapshotService snapshots;

    /** Creates a scenario service sharing the same snapshot boundary as factor analysis. */
    public ScenarioValuationService(PointInTimeStockSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * Uses next-fiscal-year institution EPS and forward-PE assumptions. A complete user PE
     * set can produce named scenarios; otherwise the result is sensitivity-only. Peer TTM
     * PE is deliberately not applied to forecast EPS because those bases are incompatible.
     */
    public ScenarioValuation analyze(
            String symbol, LocalDate asOf, Map<String, BigDecimal> userTargetPes) {
        PointInTimeStockSnapshot snapshot = snapshots.load(symbol, asOf);
        AnalystForecastConsensus consensus = new AnalystForecastConsensus();
        var currentYearForecast = consensus.summarize(
                snapshot.analystReports(), asOf, asOf.getYear());
        var nextYearForecast = consensus.summarize(
                snapshot.analystReports(), asOf, asOf.getYear() + 1);
        BigDecimal currentYearMedian = currentYearForecast.median();
        BigDecimal nextYearMedian = nextYearForecast.median();
        BigDecimal currentYearForwardPe = FactorSupport.div(
                snapshot.marketPrice().priceCny(), currentYearMedian);

        List<String> limitations = new ArrayList<>();
        Map<String, BigDecimal> targetPes = completeUserTargetPes(userTargetPes);
        boolean userRangeAvailable = !targetPes.isEmpty();
        String targetPeSource = userRangeAvailable
                ? "USER_INPUT_FORWARD_PE"
                : "SENSITIVITY_GRID_ONLY";
        String targetPeBasis = userRangeAvailable
                ? USER_FORWARD_PE_BASIS
                : SENSITIVITY_FORWARD_PE_BASIS;
        if (!userRangeAvailable) {
            limitations.add("未提供完整的悲观/基准/乐观Forward PE假设，只返回敏感性矩阵");
            limitations.add("同行TTM PE不得与下一年度预测EPS直接组合为目标价");
        }

        BigDecimal dividendYield = FactorSupport.div(
                snapshot.dividends().stream()
                        .filter(value -> !value.exDividendDate().isBefore(asOf.minusYears(1)))
                        .map(value -> value.cashPerTenShares()
                                .divide(BigDecimal.TEN, 8, RoundingMode.HALF_UP))
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                snapshot.marketPrice().priceCny());

        List<ScenarioValuation.ScenarioCase> scenarios = new ArrayList<>();
        if (nextYearMedian != null && userRangeAvailable) {
            BigDecimal[] epsCases = {
                    nextYearMedian.multiply(new BigDecimal("0.90")),
                    nextYearMedian,
                    nextYearMedian.multiply(new BigDecimal("1.10"))
            };
            String[] names = {"PESSIMISTIC", "BASE", "OPTIMISTIC"};
            for (int index = 0; index < names.length; index++) {
                BigDecimal targetPe = targetPes.get(names[index]);
                BigDecimal targetPrice = epsCases[index].multiply(targetPe);
                BigDecimal priceReturn = FactorSupport.div(
                        targetPrice, snapshot.marketPrice().priceCny()).subtract(BigDecimal.ONE);
                BigDecimal totalReturn = priceReturn.add(
                        dividendYield == null ? BigDecimal.ZERO : dividendYield);
                scenarios.add(new ScenarioValuation.ScenarioCase(
                        names[index], epsCases[index], targetPe, targetPrice,
                        FactorSupport.pct(priceReturn), FactorSupport.pct(totalReturn)));
            }
        }
        if (nextYearMedian == null) {
            limitations.add("缺少下一年度有效EPS预测");
        }

        List<BigDecimal> matrixPes = targetPes.isEmpty()
                ? List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(20), BigDecimal.valueOf(30))
                : List.copyOf(targetPes.values());
        List<ScenarioValuation.SensitivityPoint> matrix = new ArrayList<>();
        if (nextYearMedian != null) {
            BigDecimal[] epsCases = {
                    nextYearMedian.multiply(new BigDecimal("0.90")),
                    nextYearMedian,
                    nextYearMedian.multiply(new BigDecimal("1.10"))
            };
            String[] epsCaseNames = {"DOWNSIDE_10_PERCENT", "BASE", "UPSIDE_10_PERCENT"};
            for (int index = 0; index < epsCases.length; index++) {
                for (BigDecimal targetPe : matrixPes) {
                    BigDecimal targetPrice = epsCases[index].multiply(targetPe);
                    BigDecimal priceReturn = FactorSupport.div(
                            targetPrice, snapshot.marketPrice().priceCny()).subtract(BigDecimal.ONE);
                    matrix.add(new ScenarioValuation.SensitivityPoint(
                            epsCaseNames[index], epsCases[index], targetPe, targetPrice,
                            FactorSupport.pct(priceReturn)));
                }
            }
        }

        return new ScenarioValuation(
                snapshot.instrument().normalizedSymbol(),
                asOf,
                snapshot.marketPrice().priceCny(),
                snapshot.marketPrice().peTtm(),
                asOf.getYear(),
                currentYearMedian,
                currentYearForwardPe,
                asOf.getYear() + 1,
                NEXT_YEAR_EPS_BASIS,
                targetPeBasis,
                false,
                targetPeSource,
                !scenarios.isEmpty(),
                scenarios,
                matrix,
                limitations,
                List.of("eastmoney_research", snapshot.marketPrice().source()));
    }

    private Map<String, BigDecimal> completeUserTargetPes(Map<String, BigDecimal> values) {
        if (values == null || !values.keySet().containsAll(
                List.of("PESSIMISTIC", "BASE", "OPTIMISTIC"))) {
            return Map.of();
        }
        if (values.values().stream().anyMatch(value -> value == null || value.signum() <= 0)) {
            return Map.of();
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        result.put("PESSIMISTIC", values.get("PESSIMISTIC"));
        result.put("BASE", values.get("BASE"));
        result.put("OPTIMISTIC", values.get("OPTIMISTIC"));
        return result;
    }
}
