package com.stockmind.bootstrap;

import com.stockmind.bootstrap.business.tool.CorporateEventTools;
import com.stockmind.bootstrap.business.tool.FactorAnalysisTools;
import com.stockmind.bootstrap.business.tool.FinancialMetricTools;
import com.stockmind.bootstrap.business.tool.InvestmentValueTools;
import com.stockmind.bootstrap.business.tool.MarketContextTools;
import com.stockmind.bootstrap.business.tool.MarketDataTools;
import com.stockmind.bootstrap.business.tool.ResearchEvidenceTools;
import com.stockmind.bootstrap.business.tool.ScenarioValuationTools;
import com.stockmind.bootstrap.business.tool.StockAnalysisTools;
import com.stockmind.bootstrap.business.tool.TechnicalIndicatorTools;

import com.agent.javascope.tool.annotation.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

/** Verifies that every explicitly declared stock-tool schema is valid runtime JSON. */
public final class StockToolSchemaAcceptanceTest {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Class<?>> toolClasses = List.of(MarketDataTools.class, TechnicalIndicatorTools.class,
                MarketContextTools.class, CorporateEventTools.class, ResearchEvidenceTools.class,
                FinancialMetricTools.class, InvestmentValueTools.class, StockAnalysisTools.class,
                FactorAnalysisTools.class, ScenarioValuationTools.class);
        int checked = 0;
        for (Class<?> toolClass : toolClasses) {
            for (Method method : toolClass.getDeclaredMethods()) {
                AgentTool tool = method.getAnnotation(AgentTool.class);
                if (tool == null) continue;
                if (!tool.inputSchema().isBlank()) {
                    JsonNode schema = parse(mapper, tool.name(), "input_schema", tool.inputSchema());
                    JsonNode pattern = schema.path("properties").path("symbol").path("pattern");
                    if (pattern.isTextual()) {
                        require(Pattern.compile(pattern.asText()).matcher("600519").matches(),
                                tool.name() + " symbol pattern不能匹配6位股票代码");
                    }
                    checked++;
                }
                if (!tool.outputSchema().isBlank()) {
                    parse(mapper, tool.name(), "output_schema", tool.outputSchema());
                    checked++;
                }
            }
        }
        require(checked >= 10, "未检查到预期数量的显式工具Schema");
    }

    private static JsonNode parse(ObjectMapper mapper, String tool, String kind, String value) throws Exception {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new AssertionError(tool + " 的 " + kind + " 不是合法JSON: " + e.getMessage(), e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
