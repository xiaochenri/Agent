package com.stockmind.domain.factor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** PE sensitivity output with explicit earnings and multiple bases. */
public record ScenarioValuation(
        String symbol,
        LocalDate asOf,
        BigDecimal currentPrice,
        BigDecimal marketPeTtm,
        int currentFiscalYear,
        BigDecimal currentFiscalYearConsensusEps,
        BigDecimal currentFiscalYearForwardPe,
        int scenarioForecastYear,
        String scenarioEpsBasis,
        String targetPeBasis,
        boolean ttmPeComparableToTargetPe,
        String targetPeSource,
        boolean uniqueRangeAvailable,
        List<ScenarioCase> scenarios,
        List<SensitivityPoint> sensitivityMatrix,
        List<String> limitations,
        List<String> sources) {

    public ScenarioValuation {
        scenarios = List.copyOf(scenarios);
        sensitivityMatrix = List.copyOf(sensitivityMatrix);
        limitations = List.copyOf(limitations);
        sources = List.copyOf(sources);
    }

    /** One named target-price case based on a next-fiscal-year EPS forecast. */
    public record ScenarioCase(
            String name,
            BigDecimal eps,
            BigDecimal targetPe,
            BigDecimal targetPrice,
            BigDecimal expectedPriceReturnPct,
            BigDecimal expectedTotalReturnPct) {}

    /** One point in the next-year EPS/forward-PE sensitivity grid. */
    public record SensitivityPoint(
            String epsCase,
            BigDecimal eps,
            BigDecimal targetPe,
            BigDecimal targetPrice,
            BigDecimal expectedPriceReturnPct) {}
}
