package com.stockmind.bootstrap.business.tool;

import com.agent.javascope.tool.annotation.AgentTool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 股票财务指标的确定性计算工具，避免由大模型自行做数值运算。
 */
@Component
public class FinancialMetricTools extends StockToolSupport {

    private static final String CALCULATION_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^(\\\\d{6}|[A-Z]{1,5})$"},"report_period":{"type":"string","minLength":1},"net_profit":{"type":["number","null"]},"total_shares":{"type":["number","null"],"exclusiveMinimum":0},"reported_basic_eps":{"type":["number","null"]},"price":{"type":"number","exclusiveMinimum":0},"price_as_of":{"type":"string"},"eps_basis":{"type":"string"},"pe_basis":{"type":"string","enum":["static_at_price_date","raw_report_period","annualized_quarter","ttm"]}},"required":["symbol","report_period","price"],"additionalProperties":false}
            """;
    private static final String CALCULATION_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"tool":{"type":"string","enum":["financial_metric_calculator"]},"status":{"type":"string","enum":["success","failed"]},"validation_passed":{"type":"boolean"},"validation_rules":{"type":"array","items":{"type":"string"}},"validation_errors":{"type":"array","items":{"type":"string"}},"retryable":{"type":"boolean"},"error_code":{"type":"string"},"data":{"type":"object","properties":{"symbol":{"type":"string"},"report_period":{"type":"string","format":"date"},"price_as_of":{"type":"string"},"eps":{"type":"number"},"pe":{"type":"number"},"eps_basis":{"type":"string"},"pe_basis":{"type":"string"},"formula":{"type":"object"},"calculation_ready":{"type":"boolean"},"data_quality":{"type":"string","enum":["valid"]}},"required":["symbol","report_period","eps","pe","eps_basis","pe_basis","formula","calculation_ready","data_quality"],"additionalProperties":false},"metadata":{"type":"object"}},"required":["tool","status","validation_passed","validation_rules","validation_errors","retryable","error_code","data","metadata"],"additionalProperties":false}
            """;

    @AgentTool(
            name = "financial_metric_calculator",
            title = "EPS与PE计算",
            namespace = "finance.calculation",
            category = "financial_calculation",
            tags = {"stock", "eps", "pe", "calculation", "readonly"},
            inputSchema = CALCULATION_SCHEMA,
            outputSchema = CALCULATION_OUTPUT_SCHEMA,
            description = "按用户指定报告期和明确口径确定性计算EPS/PE。仅用于计算任务；已有行情TTM PE或画像估值指标时无需重复计算。季度累计EPS不能冒充年度或TTM EPS。")
    public String calculate(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        String rawReportPeriod = asString(input.get("report_period"));
        FinancialReportPeriodResolver.ResolvedPeriod resolvedPeriod =
                FinancialReportPeriodResolver.resolve(rawReportPeriod).orElse(null);
        String reportPeriod = resolvedPeriod == null ? "" : resolvedPeriod.reportPeriod();
        Double price = number(input.get("price"));
        Double reportedEps = number(input.get("reported_basic_eps"));
        Double netProfit = number(input.get("net_profit"));
        Double totalShares = number(input.get("total_shares"));
        if (symbol.isBlank()) return fail("financial_metric_calculator", StockToolError.SYMBOL_REQUIRED);
        if (reportPeriod.isBlank()) return fail("financial_metric_calculator", StockToolError.INVALID_REPORT_PERIOD);
        if (price == null || price <= 0) return fail("financial_metric_calculator", StockToolError.INVALID_PRICE);

        String epsBasis;
        Double eps;
        if (reportedEps != null) {
            eps = reportedEps;
            epsBasis = firstNonBlank(asString(input.get("eps_basis")), "reported_basic_eps");
        } else if (netProfit != null && totalShares != null && totalShares > 0) {
            eps = netProfit / totalShares;
            epsBasis = firstNonBlank(asString(input.get("eps_basis")), "net_profit/total_shares");
        } else {
            return fail("financial_metric_calculator", StockToolError.EPS_INPUT_INCOMPLETE);
        }
        if (!Double.isFinite(eps) || eps == 0D) {
            return fail("financial_metric_calculator", StockToolError.EPS_INVALID);
        }
        String peBasis = asString(input.get("pe_basis"));
        boolean annual = "ANNUAL".equals(resolvedPeriod.reportType());
        if (!annual && peBasis.isBlank())
            return fail("financial_metric_calculator", StockToolError.PE_BASIS_REQUIRED);
        peBasis = firstNonBlank(peBasis, "static_at_price_date");
        String requestedEpsBasis = asString(input.get("eps_basis")).trim().toLowerCase();
        boolean explicitTtmEps = requestedEpsBasis.equals("ttm_eps")
                || requestedEpsBasis.equals("trailing_twelve_months_eps");
        if (("ttm".equals(peBasis) && !explicitTtmEps)
                || (!annual && "static_at_price_date".equals(peBasis))) {
            return fail("financial_metric_calculator", StockToolError.PE_BASIS_INCOMPATIBLE);
        }
        if ("annualized_quarter".equals(peBasis)) {
            double factor = switch (resolvedPeriod.reportType()) {
                case "Q1" -> 4D;
                case "H1" -> 2D;
                case "Q3" -> 4D / 3D;
                default -> 1D;
            };
            eps *= factor;
            epsBasis += "*annualization_factor_" + factor;
        }
        double pe = price / eps;
        if (!Double.isFinite(pe)) return fail("financial_metric_calculator", StockToolError.PE_CALCULATION_INVALID);

        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("eps", reportedEps != null ? "使用财报披露的基本EPS" : "net_profit / total_shares");
        formula.put("pe", switch (peBasis) {
            case "raw_report_period" -> "price / raw_report_period_eps（不可与年度或TTM PE直接比较）";
            case "annualized_quarter" -> "price / annualized_quarter_eps";
            case "ttm" -> "price / explicitly_supplied_ttm_eps";
            default -> "price / annual_report_eps";
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", symbol);
        data.put("report_period", reportPeriod);
        data.put("price_as_of", asString(input.get("price_as_of")));
        data.put("eps", eps);
        data.put("pe", pe);
        data.put("eps_basis", epsBasis);
        data.put("pe_basis", peBasis);
        data.put("formula", formula);
        data.put("calculation_ready", true);
        data.put("data_quality", "valid");
        return success("financial_metric_calculator", data,
                "[\"输入数值非空且分母非零\",\"EPS和PE由确定性公式计算\",\"输出计算口径\"]");
    }
}
