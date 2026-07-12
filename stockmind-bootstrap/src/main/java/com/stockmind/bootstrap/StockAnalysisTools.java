package com.stockmind.bootstrap;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.analysis.StockEvidenceBundle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * The only business tool that turns normalized evidence into user-facing analysis.
 */
@Component
public class StockAnalysisTools extends StockToolSupport {
    private static final String SNAPSHOT_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string"},"name":{"type":"string"},"quote_data":{"type":"object"},"technical_data":{"type":"object"},"bars_data":{"type":"object"},"news_data":{"type":"object"},"fundamental_data":{"type":"object"}},"required":["symbol"]}
            """;

    @AgentTool(
            name = "stock_snapshot_analysis",
            title = "股票证据汇总分析",
            namespace = "finance.analysis",
            category = "stock_analysis",
            tags = {"stock", "analysis", "summary", "readonly"},
            inputSchema = SNAPSHOT_SCHEMA,
            description = "唯一面向用户的分析工具。规整 quote_data、technical_data、bars_data、news_data、fundamental_data，校验时间/来源/数据模式后输出结论、证据、冲突和局限；不重新计算指标。")
    public String stockSnapshotAnalysis(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("stock_snapshot_analysis", "symbol 不能为空", false);

        StockEvidenceBundle bundle = new StockEvidenceBundle(
                symbol.toUpperCase(),
                map(input.get("quote_data")),
                map(input.get("technical_data")),
                map(input.get("bars_data")),
                map(input.get("news_data")),
                map(input.get("fundamental_data")),
                new ArrayList<>());
        Map<String, Object> data = analyze(bundle, asString(input.get("name")));
        return success("stock_snapshot_analysis", data,
                "[\"symbol 非空\",\"证据已规整\",\"已校验时间/来源/数据模式\",\"不包含直接买卖建议\"]");
    }

    private Map<String, Object> analyze(StockEvidenceBundle bundle, String name) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> quote = bundle.quote();
        Map<String, Object> technical = bundle.technicalIndicators();
        validateIdentity(bundle.symbol(), quote, "quote_data", warnings);
        validateIdentity(bundle.symbol(), technical, "technical_data", warnings);
        validateTime(quote, technical, warnings);
        validateSource(quote, technical, warnings);

        String quoteTime = firstNonBlank(asString(quote.get("as_of")), asString(technical.get("as_of")));
        List<Map<String, Object>> evidence = new ArrayList<>();
        addEvidence(evidence, "MARKET_QUOTE", quote, List.of("price", "change_pct", "volume", "as_of", "source"));
        addEvidence(evidence, "TECHNICAL_INDICATORS", technical,
                List.of("moving_average", "macd", "rsi", "bollinger", "atr", "volume_indicator", "signals", "as_of", "source"));
        addEvidence(evidence, "NEWS", bundle.news(), List.of("items", "source", "data_mode"));
        addEvidence(evidence, "FUNDAMENTALS", bundle.fundamentals(), List.of("items", "source", "data_mode"));

        String trend = direction(technical, "trend");
        String momentum = direction(technical, "momentum");
        List<String> risks = new ArrayList<>();
        if (!trend.isBlank() && !momentum.isBlank() && !trend.equals(momentum))
            risks.add("技术趋势与动量信号冲突，不能将单一方向解释为确定性结论。");
        if (bundle.fundamentals().isEmpty()) risks.add("缺少财报、估值和经营质量证据，基本面结论的覆盖度有限。");

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("level", warnings.isEmpty() ? "CONSISTENT" : "PARTIAL");
        quality.put("can_assess_investment_value", bundle.fundamentals().get("items") != null && warnings.isEmpty());
        quality.put("warnings", warnings);

        Map<String, Object> readiness = analysisReadiness(bundle, warnings);
        Map<String, Object> answerContext = answerContext(bundle, name, quoteTime, quote, technical, trend, momentum, risks);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", bundle.symbol());
        data.put("name", name);
        data.put("as_of", quoteTime.isBlank() ? LocalDate.now().toString() : quoteTime);
        data.put("data_quality", quality);
        data.put("analysis_readiness", readiness);
        data.put("decision", decision(bundle, readiness));
        data.put("answer_context", answerContext);
        data.put("market_view", quote);
        data.put("technical_view", Map.of("trend", trend, "momentum", momentum,
                "signals", technical.getOrDefault("signals", List.of()),
                "indicator_snapshot", technical));
        data.put("fundamental_view", bundle.fundamentals());
        data.put("evidence", evidence);
        data.put("risk_points", risks);
        data.put("conclusion_boundary", "结论基于当前可用证据，不构成投资建议。");
        data.put("next_actions", List.of("补充真实行情、新闻及财报/估值证据。", "若技术信号冲突，观察后续交易日确认而非据单点信号决策。"));
        return data;
    }

    /** Compact, user-facing synthesis passed to the final model together with the decision. */
    private Map<String, Object> answerContext(
            StockEvidenceBundle bundle,
            String name,
            String asOf,
            Map<String, Object> quote,
            Map<String, Object> technical,
            String trend,
            String momentum,
            List<String> inheritedRisks) {
        String displayName = name == null || name.isBlank() ? bundle.symbol() : name;
        Double price = number(quote.get("price"));
        Double dailyChange = number(quote.get("change_pct"));
        Double rsi = nestedNumber(technical, "rsi", "rsi");
        Double volumeRatio = nestedNumber(technical, "volume_indicator", "volume_ratio");
        Double percentB = nestedNumber(technical, "bollinger", "percent_b");
        String macdState = nestedText(technical, "macd", "state");

        List<String> conclusions = new ArrayList<>();
        conclusions.add(displayName + "截至" + asOf + "的行情显示，最新价"
                + value(price) + "，当日涨跌幅" + percent(dailyChange) + "。");
        conclusions.add("技术面呈" + directionZh(trend) + "：均线状态与MACD"
                + (macdState.isBlank() ? "" : "（" + macdState + "）") + "共同支持当前趋势，动量为" + directionZh(momentum) + "。");
        boolean overheated = (rsi != null && rsi >= 70) || (percentB != null && percentB > 1);
        conclusions.add(overheated
                ? "短线处于偏热区，当前更适合等待回撤或量能确认，不宜只因趋势偏强而追高。"
                : "技术面尚未出现明显过热信号，可结合后续量能和价格结构继续观察。");
        conclusions.add("综合判断：当前具备技术面观察价值；长期投资价值仍应结合盈利、现金流与估值等基本面指标作进一步确认。");

        List<String> evidence = new ArrayList<>();
        evidence.add("最新价" + value(price) + "，当日涨跌幅" + percent(dailyChange) + "。");
        evidence.add("EMA趋势=" + trend + "，MACD=" + macdState + "，RSI=" + value(rsi) + "。");
        if (percentB != null) evidence.add("布林带%B=" + value(percentB) + (percentB > 1 ? "，价格位于上轨之外。" : "。"));
        if (volumeRatio != null) evidence.add("量比=" + value(volumeRatio) + (volumeRatio < 1 ? "，当前量能低于均量。" : "。"));

        List<String> risks = new ArrayList<>(inheritedRisks);
        if (rsi != null && rsi >= 70) risks.add("RSI 为" + value(rsi) + "，处于超买区，短线波动和回撤风险上升。");
        if (percentB != null && percentB > 1) risks.add("价格突破布林带上轨，强势延续与短线回归均有可能，需等待确认。");
        if (volumeRatio != null && volumeRatio < 1) risks.add("量比低于1，当前上涨缺少相对放量确认。");
        risks.add("基本面检索当前未提供营收、利润、现金流和估值等量化字段，长期价值判断需补充这些指标。");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("subject", displayName + "（" + bundle.symbol() + "）");
        context.put("as_of", asOf);
        context.put("core_conclusions", conclusions);
        context.put("key_evidence", evidence);
        context.put("risk_points", risks.stream().distinct().toList());
        context.put("next_actions", List.of("关注回撤后的价格承接与量能变化。", "补充营收、利润、现金流和估值数据后，再评估长期配置价值。"));
        return context;
    }

    /** General decision contract consumed by the runtime without stock-specific branching. */
    private Map<String, Object> decision(StockEvidenceBundle bundle, Map<String, Object> readiness) {
        boolean ready = Boolean.TRUE.equals(readiness.get("ready_for_answer"));
        List<String> completedScopes = new ArrayList<>();
        List<String> missingScopes = new ArrayList<>();
        if (hasContent(bundle.quote())) completedScopes.add("quote");
        else missingScopes.add("quote");
        if (hasContent(bundle.technicalIndicators())) completedScopes.add("technical");
        else missingScopes.add("technical");
        if (hasContent(bundle.marketBars())) completedScopes.add("bars");
        if (hasContent(bundle.news())) completedScopes.add("news");
        if (hasContent(bundle.fundamentals())) completedScopes.add("fundamental");
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("decision_key", "stock_snapshot:" + bundle.symbol());
        decision.put("status", ready ? "COMPLETED" : "EVIDENCE_INCOMPLETE");
        decision.put("recommended_action", readiness.get("recommended_next_action"));
        decision.put("completed_scopes", completedScopes);
        decision.put("missing_scopes", missingScopes);
        decision.put("unresolved_reasons", readiness.get("required_gaps"));
        decision.put("repeat_policy", ready ? "REUSE_EXISTING_RESULT" : "COLLECT_MISSING_EVIDENCE");
        decision.put("reason", readiness.get("guidance"));
        return decision;
    }

    /**
     * Business-level completion signal for the next reasoning turn. It does not terminate the
     * Agent: the model may still collect evidence for a concrete unresolved question.
     */
    private Map<String, Object> analysisReadiness(StockEvidenceBundle bundle, List<String> consistencyWarnings) {
        List<String> covered = new ArrayList<>();
        List<String> requiredGaps = new ArrayList<>();
        List<String> optionalGaps = new ArrayList<>();
        List<String> suggestedTools = new ArrayList<>();
        int score = 0;

        if (hasContent(bundle.quote())) {
            covered.add("行情");
            score += 30;
        } else {
            requiredGaps.add("缺少行情数据");
            suggestedTools.add("market_quote");
        }
        if (hasContent(bundle.technicalIndicators())) {
            covered.add("技术指标");
            score += 45;
        } else {
            requiredGaps.add("缺少技术指标快照");
            suggestedTools.add("technical_indicator_snapshot");
        }
        if (hasContent(bundle.news())) {
            covered.add("新闻事件");
            score += 10;
        } else {
            optionalGaps.add("可补充新闻事件证据");
            suggestedTools.add("news_search");
        }
        if (hasContent(bundle.fundamentals())) {
            covered.add("基本面");
            score += 15;
        } else {
            optionalGaps.add("可补充基本面与估值证据");
            suggestedTools.add("knowledge_search");
        }
        if (!consistencyWarnings.isEmpty()) {
            requiredGaps.add("存在数据一致性问题：" + String.join("；", consistencyWarnings));
        }

        boolean ready = requiredGaps.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready_for_answer", ready);
        result.put("coverage_score", score);
        result.put("covered_dimensions", covered);
        result.put("required_gaps", requiredGaps);
        result.put("optional_gaps", optionalGaps);
        result.put("suggested_tools", suggestedTools.stream().distinct().toList());
        result.put("recommended_next_action", ready ? "FINAL_ANSWER" : "COLLECT_REQUIRED_EVIDENCE");
        result.put("guidance", ready
                ? "当前证据已足以回答用户问题；仅当需要解决明确的未覆盖问题时才继续调用工具。"
                : "请只调用 suggested_tools 中能填补 required_gaps 的工具，然后重新汇总分析。");
        return result;
    }

    private boolean hasContent(Map<String, Object> source) {
        return source != null && !source.isEmpty()
                && (source.get("items") == null || !((source.get("items") instanceof List<?> items) && items.isEmpty()));
    }

    private Double nestedNumber(Map<String, Object> source, String group, String field) {
        return number(map(source.get(group)).get(field));
    }

    private String nestedText(Map<String, Object> source, String group, String field) {
        return asString(map(source.get(group)).get(field));
    }

    private String value(Double number) {
        return number == null ? "--" : String.format(java.util.Locale.ROOT, "%.2f", number);
    }

    private String percent(Double number) {
        return number == null ? "--" : String.format(java.util.Locale.ROOT, "%+.2f%%", number);
    }

    private String directionZh(String direction) {
        return switch (direction) {
            case "BULLISH" -> "偏强";
            case "BEARISH" -> "偏弱";
            default -> "中性";
        };
    }

    private void validateIdentity(String symbol, Map<String, Object> source, String label, List<String> warnings) {
        String actual = asString(source.get("symbol"));
        if (!actual.isBlank() && !symbol.equalsIgnoreCase(actual))
            warnings.add(label + " 的 symbol=" + actual + " 与分析标的不一致。");
    }

    private void validateTime(Map<String, Object> quote, Map<String, Object> technical, List<String> warnings) {
        String quoteTime = asString(quote.get("as_of"));
        String technicalTime = firstNonBlank(asString(technical.get("end_at")), asString(technical.get("as_of")));
        if (!quoteTime.isBlank() && !technicalTime.isBlank() && !quoteTime.equals(technicalTime))
            warnings.add("TIME_RANGE_MISMATCH：行情时间 " + quoteTime + " 与技术指标时间 " + technicalTime + " 不一致，不能合并为当前结论。");
    }

    private void validateSource(Map<String, Object> quote, Map<String, Object> technical, List<String> warnings) {
        String quoteSource = asString(quote.get("source")), technicalSource = asString(technical.get("source"));
        if (!quoteSource.isBlank() && !technicalSource.isBlank() && !quoteSource.equals(technicalSource))
            warnings.add("SOURCE_MISMATCH：行情与技术指标数据源不一致。");
        String quoteAdjustment = asString(quote.get("adjustment")), technicalAdjustment = asString(technical.get("adjustment"));
        if (!quoteAdjustment.isBlank() && !technicalAdjustment.isBlank() && !quoteAdjustment.equals(technicalAdjustment))
            warnings.add("ADJUSTMENT_MISMATCH：行情与技术指标复权口径不一致。");
    }

    private void addEvidence(List<Map<String, Object>> all, String type, Map<String, Object> source, List<String> fields) {
        if (source.isEmpty()) return;
        Map<String, Object> value = new LinkedHashMap<>();
        for (String field : fields) if (source.containsKey(field)) value.put(field, source.get(field));
        all.add(Map.of("type", type, "data", value));
    }

    private String direction(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?, ?> map && map.get("direction") != null ? String.valueOf(map.get("direction")) : "NEUTRAL";
    }
}
