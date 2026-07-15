package com.agent.javascope.context.projection;

import com.agent.javascope.context.budget.PromptBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 默认窗口化投影：完整日志保存在轨迹中，仅选择最近片段进入 Prompt。 */
public class InMemoryContextManager implements ContextManager {

    /**
     * {@inheritDoc}
     *
     * <p>默认策略保留最近历史、当前约束及轻量证据索引；原始日志继续由 ExecutionLogStore 保存。</p>
     */
    @Override
    public WorkingContext project(ContextRequest request, PromptBudget budget) {
        JsonNode executionLog = request.executionLog();
        // 历史窗口优先保留每个工具最近一次结果和最近一次成功结果，再用最新普通日志补足。
        // 这样 reasoning 日志不会把仍然有效的早期工具证据挤出上下文。
        ArrayNode history = selectRelevantHistory(executionLog, budget.maxHistoryItems());

        // 校验反馈、短期记忆和风险标记共同构成本轮不可忽略的约束。
        ArrayNode constraints = JsonNodeFactory.instance.arrayNode();
        if (!request.validationFeedback().isBlank()) {
            constraints.add(request.validationFeedback());
        }
        appendAll(constraints, request.ephemeralMemory());
        appendAll(constraints, request.riskFlags());

        // 证据摘要包含工具状态、调用参数和关键数据；完整输出仍保存在执行轨迹中。
        ArrayNode evidence = JsonNodeFactory.instance.arrayNode();
        // 决策包是业务工具对“下一步该做什么”的显式判断，必须优先于普通工具索引进入模型上下文。
        JsonNode businessDecisions = request.businessDecisions();
        if (businessDecisions.isArray()) {
            for (JsonNode decision : businessDecisions) {
                if (evidence.size() >= budget.maxEvidenceItems()) {
                    break;
                }
                ObjectNode summary = JsonNodeFactory.instance.objectNode();
                summary.put("type", "business_decision");
                summary.set("decision", decision);
                evidence.add(summary);
            }
        }
        List<JsonNode> toolEvidence = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (evidence.size() >= budget.maxEvidenceItems()) {
                break;
            }
            JsonNode item = history.get(i);
            if (isToolObservation(item)) {
                toolEvidence.add(buildObservation(item));
            }
        }
        Collections.reverse(toolEvidence);
        for (JsonNode summary : toolEvidence) {
            if (evidence.size() >= budget.maxEvidenceItems()) break;
            evidence.add(summary);
        }

        ArrayNode latestObservations = latestToolObservations(executionLog, budget.maxEvidenceItems());
        return new WorkingContext(
                request.currentPlan(), history, constraints, evidence,
                latestObservations, request.investigationState());
    }

    private ArrayNode selectRelevantHistory(JsonNode executionLog, int limit) {
        ArrayNode selectedHistory = JsonNodeFactory.instance.arrayNode();
        if (!executionLog.isArray() || executionLog.isEmpty()) return selectedHistory;

        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        Set<String> latestOutcomeTools = new HashSet<>();
        selectToolEntries(executionLog, limit, selected, latestOutcomeTools, false);

        // 工具最近一次调用可能失败，但更早的成功观察仍可能对当前判断有效。
        Set<String> latestSuccessfulTools = new HashSet<>();
        selectToolEntries(executionLog, limit, selected, latestSuccessfulTools, true);

        for (int i = executionLog.size() - 1; i >= 0 && selected.size() < limit; i--) {
            selected.add(i);
        }
        List<Integer> indexes = new ArrayList<>(selected);
        Collections.sort(indexes);
        for (Integer index : indexes) selectedHistory.add(executionLog.get(index));
        return selectedHistory;
    }

    private void selectToolEntries(
            JsonNode executionLog,
            int limit,
            Set<Integer> selected,
            Set<String> seenTools,
            boolean successOnly) {
        for (int i = executionLog.size() - 1; i >= 0 && selected.size() < limit; i--) {
            JsonNode item = executionLog.get(i);
            if (!isToolObservation(item)) continue;
            JsonNode output = item.path("output");
            if (successOnly && !"success".equals(output.path("status").asText())) continue;
            String toolName = item.path("tool_name").asText("");
            if (seenTools.add(toolName)) selected.add(i);
        }
    }

    private ArrayNode latestToolObservations(JsonNode executionLog, int limit) {
        ArrayNode observations = JsonNodeFactory.instance.arrayNode();
        if (!executionLog.isArray()) return observations;
        Set<String> seenTools = new HashSet<>();
        List<JsonNode> latest = new ArrayList<>();
        for (int i = executionLog.size() - 1; i >= 0 && latest.size() < limit; i--) {
            JsonNode item = executionLog.get(i);
            if (!isToolObservation(item)) continue;
            String toolName = item.path("tool_name").asText("");
            if (seenTools.add(toolName)) latest.add(buildObservation(item));
        }
        Collections.reverse(latest);
        latest.forEach(observations::add);
        return observations;
    }

    private boolean isToolObservation(JsonNode item) {
        return item != null
                && item.isObject()
                && !item.path("tool_name").asText("").isBlank()
                && item.path("output").isObject()
                && item.path("output").path("status").isTextual();
    }

    private ObjectNode buildObservation(JsonNode item) {
        JsonNode output = item.path("output");
        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.put("source_step", item.path("step").asText());
        summary.put("tool_name", item.path("tool_name").asText());
        summary.put("status", output.path("status").asText());
        summary.put("retryable", output.path("retryable").asBoolean(false));
        if (!output.path("error_code").asText("").isBlank()) {
            summary.put("error_code", output.path("error_code").asText());
        }
        if (!item.path("input").isMissingNode() && !item.path("input").isNull()) {
            summary.set("input", compact(item.path("input"), 0));
        }
        if (!output.path("data").isMissingNode() && !output.path("data").isNull()) {
            summary.set("key_data", compact(output.path("data"), 0));
        }
        if (output.path("validation_errors").isArray() && !output.path("validation_errors").isEmpty()) {
            summary.set("errors", compact(output.path("validation_errors"), 0));
        }
        return summary;
    }

    /** 对工具数据做有界递归压缩，保留决策所需内容而不把完整大结果重复放入 Prompt。 */
    private JsonNode compact(JsonNode value, int depth) {
        if (value == null || value.isNull() || value.isMissingNode()) return NullNode.getInstance();
        if (value.isTextual()) {
            String text = value.asText();
            return TextNode.valueOf(text.length() <= 300 ? text : text.substring(0, 300) + "…");
        }
        if (value.isValueNode()) return value.deepCopy();
        if (depth >= 3) return TextNode.valueOf("[nested data omitted]");
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            int size = Math.min(value.size(), 3);
            for (int i = 0; i < size; i++) result.add(compact(value.get(i), depth + 1));
            if (value.size() > size) result.add("… " + (value.size() - size) + " more items");
            return result;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        int count = 0;
        var fields = value.fields();
        while (fields.hasNext() && count < 12) {
            var field = fields.next();
            result.set(field.getKey(), compact(field.getValue(), depth + 1));
            count++;
        }
        if (fields.hasNext()) result.put("_truncated", true);
        return result;
    }

    /**
     * 将数组形式的短期记忆或风险标记追加到目标约束列表。
     *
     * @param target 接收元素的约束数组
     * @param source 可选的数组 JSON 载荷
     */
    private void appendAll(ArrayNode target, JsonNode source) {
        if (source.isArray()) {
            source.forEach(target::add);
        }
    }
}
