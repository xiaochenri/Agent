package com.stockmind.bootstrap;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.tool.contract.ToolSemanticValidator;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 财报取数和 EPS/PE 计算的业务语义校验，补充 JSON Schema 无法表达的跨字段关系。 */
@Component
public class StockFinancialToolSemanticValidator implements ToolSemanticValidator {

    @Override
    public boolean supports(String toolName) {
        return "financial_report_metrics".equals(toolName)
                || "financial_metric_calculator".equals(toolName);
    }

    @Override
    public List<String> validateInput(AgentToolDefinition definition, JsonNode input) {
        List<String> errors = new ArrayList<>();
        String period = input.path("report_period").asText("");
        if (FinancialReportPeriodResolver.resolve(period).isEmpty()) {
            errors.add("report_period 无法归一化为合法财报截止日");
        }
        if ("financial_metric_calculator".equals(definition.getName())) {
            double price = input.path("price").asDouble(Double.NaN);
            if (!Double.isFinite(price) || price <= 0D) errors.add("price 必须是正数");
        }
        return errors;
    }

    @Override
    public List<String> validateOutput(
            AgentToolDefinition definition, JsonNode input, ToolExecutionResult result) {
        List<String> errors = new ArrayList<>();
        JsonNode data = result.data();
        FinancialReportPeriodResolver.ResolvedPeriod expected =
                FinancialReportPeriodResolver.resolve(input.path("report_period").asText("")).orElse(null);
        if (expected != null && !expected.reportPeriod().equals(data.path("report_period").asText())) {
            errors.add("输出 report_period 与归一化输入不一致");
        }
        if ("financial_report_metrics".equals(definition.getName())) {
            boolean reportedEps = data.path("reported_basic_eps").isNumber();
            boolean calculatedSource = data.path("net_profit").isNumber() && data.path("total_shares").isNumber();
            boolean ready = data.path("calculation_ready").asBoolean(false);
            if (ready != (reportedEps || calculatedSource)) {
                errors.add("calculation_ready 与可用 EPS 来源不一致");
            }
            if (ready && data.path("source_documents").isEmpty()) {
                errors.add("财报指标可计算时必须提供 source_documents");
            }
        } else {
            double eps = data.path("eps").asDouble(Double.NaN);
            double pe = data.path("pe").asDouble(Double.NaN);
            double price = input.path("price").asDouble(Double.NaN);
            if (!Double.isFinite(eps) || eps == 0D || !Double.isFinite(pe)
                    || Math.abs(pe - price / eps) > Math.max(0.000001D, Math.abs(pe) * 0.000001D)) {
                errors.add("PE 与 price / EPS 的确定性公式不一致");
            }
        }
        return errors;
    }
}
