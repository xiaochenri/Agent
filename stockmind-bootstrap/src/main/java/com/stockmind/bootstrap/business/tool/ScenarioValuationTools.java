package com.stockmind.bootstrap.business.tool;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.factor.ScenarioValuationService;
import com.stockmind.domain.factor.ScenarioValuation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Thin adapter for forecast-EPS and forward-PE sensitivity analysis. */
@Component
public final class ScenarioValuationTools extends StockToolSupport {
    private static final String SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^((SH|SZ|BJ)?\\\\d{6})$"},"as_of":{"type":"string","format":"date"},"pessimistic_pe":{"type":"number","exclusiveMinimum":0},"base_pe":{"type":"number","exclusiveMinimum":0},"optimistic_pe":{"type":"number","exclusiveMinimum":0}},"required":["symbol"],"additionalProperties":false}
            """;

    private final ScenarioValuationService service;

    /** Creates the tool adapter for the scenario valuation service. */
    public ScenarioValuationTools(ScenarioValuationService service) {
        this.service = service;
    }

    @AgentTool(name = "scenario_valuation_analysis", title = "PE情景估值与敏感性矩阵",
            namespace = "finance.factor", category = "scenario_valuation",
            tags = {"stock", "valuation", "scenario", "readonly"}, inputSchema = SCHEMA,
            description = "使用下一财年机构EPS中位数与用户给定Forward PE假设生成情景估值；假设不完整时返回敏感性矩阵而非唯一目标价。")
    /** Validates optional PE inputs and exposes every earnings/multiple basis explicitly. */
    public String analyze(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) {
            return fail("scenario_valuation_analysis", StockToolError.SYMBOL_REQUIRED);
        }
        try {
            LocalDate asOf = asString(input.get("as_of")).isBlank()
                    ? LocalDate.now(CHINA_ZONE)
                    : LocalDate.parse(asString(input.get("as_of")));
            Map<String, BigDecimal> targetPes = new LinkedHashMap<>();
            put(targetPes, "PESSIMISTIC", input.get("pessimistic_pe"));
            put(targetPes, "BASE", input.get("base_pe"));
            put(targetPes, "OPTIMISTIC", input.get("optimistic_pe"));
            ScenarioValuation valuation = service.analyze(symbol, asOf, targetPes);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", valuation.symbol());
            data.put("as_of", valuation.asOf().toString());
            data.put("current_price", valuation.currentPrice());
            data.put("market_pe_ttm", valuation.marketPeTtm());
            data.put("market_pe_basis", "TRAILING_TWELVE_MONTHS");
            data.put("current_fiscal_year", valuation.currentFiscalYear());
            data.put("current_fiscal_year_consensus_eps",
                    valuation.currentFiscalYearConsensusEps());
            data.put("current_fiscal_year_forward_pe",
                    valuation.currentFiscalYearForwardPe());
            data.put("current_fiscal_year_forward_pe_basis",
                    "CURRENT_FISCAL_YEAR_INSTITUTION_CONSENSUS_MEDIAN");
            data.put("scenario_forecast_year", valuation.scenarioForecastYear());
            data.put("scenario_eps_basis", valuation.scenarioEpsBasis());
            data.put("target_pe_basis", valuation.targetPeBasis());
            data.put("ttm_pe_comparable_to_target_pe", valuation.ttmPeComparableToTargetPe());
            data.put("target_pe_source", valuation.targetPeSource());
            data.put("unique_range_available", valuation.uniqueRangeAvailable());
            data.put("scenarios", valuation.scenarios());
            data.put("sensitivity_matrix", valuation.sensitivityMatrix());
            data.put("analysis_capability", Map.of(
                    "capability", "SCENARIO_VALUATION",
                    "resolution_mode", "AVAILABLE",
                    "updates_agenda_ids", java.util.List.of("valuation_safety_margin")));
            data.put("limitations", valuation.limitations());
            data.put("sources", valuation.sources());
            return success("scenario_valuation_analysis", data,
                    "[\"目标价=下一财年机构EPS中位数×Forward PE假设\","
                            + "\"TTM PE与Forward PE不可直接比较\","
                            + "\"未提供完整Forward PE假设时不输出唯一合理价格\","
                            + "\"机构预测属于观点\"]");
        } catch (IllegalArgumentException exception) {
            return fail("scenario_valuation_analysis", StockToolError.INVALID_FACTOR_PROFILE_INPUT);
        } catch (Exception exception) {
            return fail("scenario_valuation_analysis", StockToolError.FACTOR_PROFILE_UNAVAILABLE);
        }
    }

    private void put(Map<String, BigDecimal> values, String key, Object value) {
        if (value != null && !asString(value).isBlank()) {
            values.put(key, new BigDecimal(asString(value)));
        }
    }
}
