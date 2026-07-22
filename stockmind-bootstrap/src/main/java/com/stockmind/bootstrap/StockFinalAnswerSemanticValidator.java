package com.stockmind.bootstrap;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.verifier.FinalAnswerSemanticValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 校验最终回答是否使用了股票业务分析中间层。
 *
 * <p>这里不维护自然语言黑名单。中间层已经给出投资立场、业务信号和解释边界，
 * 校验器只阻断可以由结构化数据确定为错误的投资立场。结论与可读论据的关联由通用
 * evidence contract 校验；signal_id 是内部审计信息，不应因为模型漏填或误写而让回答空转。</p>
 */
@Component
public final class StockFinalAnswerSemanticValidator implements FinalAnswerSemanticValidator {
    private final AgentJsonCodecUtil json;

    public StockFinalAnswerSemanticValidator(AgentJsonCodecUtil json) {
        this.json = json;
    }

    @Override
    public List<String> validate(
            String userInput,
            List<AgentExecutionLogEntry> executionLog,
            Map<String, Object> finalAnswer) {
        if (userInput == null || !userInput.contains("投资价值")) return List.of();

        Map<String, Object> profile = latestSuccessfulData(executionLog, "stock_factor_profile");
        Map<String, Object> analysis = json.asMap(profile.get("investment_analysis"));
        if (analysis.isEmpty()) return List.of();

        List<String> issues = new ArrayList<>();
        Map<String, String> signalCatalog = signalCatalog(analysis, executionLog);
        validateStance(analysis, finalAnswer, signalCatalog, issues);
        return issues.stream().distinct().toList();
    }

    /** 投资立场由业务层给出，模型负责解释，不再重新创造另一套立场。 */
    private void validateStance(
            Map<String, Object> analysis,
            Map<String, Object> finalAnswer,
            Map<String, String> signalCatalog,
            List<String> issues) {
        String expected = text(analysis.get("stance"));
        String actual = text(finalAnswer.get("investment_stance"));
        if (actual.isBlank()) {
            List<String> conclusions = json.asStringList(finalAnswer.get("core_conclusions"));
            String first = conclusions.isEmpty() ? "" : conclusions.get(0);
            if (!first.contains(expected)) {
                issues.add("投资价值回答需在investment_stance或第一条核心结论中明确业务分析立场：expected="
                        + expected);
            }
        } else if (!expected.equals(actual)) {
            List<String> revisionIds = json.asStringList(finalAnswer.get("stance_revision_signal_ids"));
            boolean materialRevision = !revisionIds.isEmpty() && revisionIds.stream().allMatch(id ->
                    signalCatalog.containsKey(id)
                            && !"UNKNOWN".equals(signalCatalog.get(id))
                            && !"NEUTRAL".equals(signalCatalog.get(id)));
            if (!materialRevision) {
                issues.add("investment_stance与基础业务分析不一致；调整立场时需通过stance_revision_signal_ids引用新增方向性业务信号：expected="
                        + expected + ", actual=" + actual);
            }
        }
    }

    /** 汇总基础画像和后续工具产出的业务信号，允许新增证据更新投资论点。 */
    private Map<String, String> signalCatalog(
            Map<String, Object> analysis, List<AgentExecutionLogEntry> executionLog) {
        Map<String, String> catalog = new java.util.LinkedHashMap<>();
        addSignals(catalog, analysis.get("signals"));
        if (executionLog != null) {
            for (AgentExecutionLogEntry entry : executionLog) {
                Map<String, Object> output = json.asMap(entry.getOutput());
                if (!"success".equals(text(output.get("status")))
                        || !json.asBoolean(output.get("validation_passed"), false)) continue;
                addSignals(catalog, json.asMap(output.get("data")).get("business_signals"));
            }
        }
        return catalog;
    }

    private void addSignals(Map<String, String> catalog, Object rawSignals) {
        if (!(rawSignals instanceof List<?> signals)) return;
        for (Object rawSignal : signals) {
            Map<String, Object> signal = json.asMap(rawSignal);
            String id = text(signal.get("signal_id"));
            if (!id.isBlank()) catalog.put(id, text(signal.get("direction")));
        }
    }

    private Map<String, Object> latestSuccessfulData(
            List<AgentExecutionLogEntry> executionLog, String toolName) {
        if (executionLog == null) return Map.of();
        for (int i = executionLog.size() - 1; i >= 0; i--) {
            AgentExecutionLogEntry entry = executionLog.get(i);
            if (!toolName.equals(entry.getToolName())) continue;
            Map<String, Object> output = json.asMap(entry.getOutput());
            if ("success".equals(text(output.get("status")))
                    && json.asBoolean(output.get("validation_passed"), false)) {
                return json.asMap(output.get("data"));
            }
        }
        return Map.of();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
