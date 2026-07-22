package com.stockmind.bootstrap;

import com.stockmind.bootstrap.business.tool.FinancialMetricTools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Prevents cumulative quarterly EPS from being silently treated as annual comparable EPS. */
public final class FinancialMetricBasisAcceptanceTest {
    public static void main(String[] args) throws Exception {
        FinancialMetricTools tools = new FinancialMetricTools();
        ObjectMapper mapper = new ObjectMapper();
        var rejected = mapper.readTree(tools.calculate(Map.of("symbol", "600519",
                "report_period", "2026-03-31", "reported_basic_eps", 21.76, "price", 1270), ""));
        require("PE_BASIS_REQUIRED".equals(rejected.path("error_code").asText()),
                "季度EPS缺少PE口径时必须拒绝计算");
        var falseTtm = mapper.readTree(tools.calculate(Map.of("symbol", "603259",
                "report_period", "2026-03-31", "reported_basic_eps", 1.59, "price", 123.04,
                "pe_basis", "ttm"), ""));
        require("PE_BASIS_INCOMPATIBLE".equals(falseTtm.path("error_code").asText()),
                "季度披露EPS不得被标记为TTM口径");
        var annualized = mapper.readTree(tools.calculate(Map.of("symbol", "600519",
                "report_period", "2026-03-31", "reported_basic_eps", 21.76, "price", 1270,
                "pe_basis", "annualized_quarter"), ""));
        require("success".equals(annualized.path("status").asText()), "明确年化口径后应允许计算");
        double pe = annualized.path("data").path("pe").asDouble();
        require(Math.abs(pe - 1270D / (21.76D * 4D)) < 0.000001D, "季度EPS年化PE计算错误");
        var explicitTtm = mapper.readTree(tools.calculate(Map.of("symbol", "603259",
                "report_period", "2026-03-31", "reported_basic_eps", 6.74, "price", 123.04,
                "eps_basis", "ttm_eps", "pe_basis", "ttm"), ""));
        require("success".equals(explicitTtm.path("status").asText()), "显式TTM EPS应允许计算");
        require(Math.abs(explicitTtm.path("data").path("pe").asDouble() - 123.04D / 6.74D)
                        < 0.000001D,
                "显式TTM EPS的PE计算错误");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
