package com.agent.javascope.context.projection;

import com.agent.javascope.context.budget.PromptBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 默认窗口化投影：完整日志保存在轨迹中，仅选择最近片段进入 Prompt。 */
public class InMemoryContextManager implements ContextManager {

    /**
     * {@inheritDoc}
     *
     * <p>默认策略保留最近历史、当前约束及轻量证据索引；原始日志继续由 ExecutionLogStore 保存。</p>
     */
    @Override
    public WorkingContext project(ContextRequest request, PromptBudget budget) {
        // 仅选择最近的窗口，避免完整日志无限增长并干扰当前决策。
        ArrayNode history = JsonNodeFactory.instance.arrayNode();
        JsonNode executionLog = request.executionLog();
        int size = executionLog.isArray() ? executionLog.size() : 0;
        int start = Math.max(0, size - budget.maxHistoryItems());
        for (int i = start; i < size; i++) {
            history.add(executionLog.get(i));
        }

        // 校验反馈、短期记忆和风险标记共同构成本轮不可忽略的约束。
        ArrayNode constraints = JsonNodeFactory.instance.arrayNode();
        if (!request.validationFeedback().isBlank()) {
            constraints.add(request.validationFeedback());
        }
        appendAll(constraints, request.ephemeralMemory());
        appendAll(constraints, request.riskFlags());

        // 证据只保留来源索引；完整工具输出仍可从执行轨迹中获取。
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
        for (JsonNode item : history) {
            if (evidence.size() >= budget.maxEvidenceItems()) {
                break;
            }
            JsonNode output = item.path("output");
            String toolName = item.path("tool_name").asText("");
            if (!output.isMissingNode() && !output.isNull() && !"reasoning".equals(toolName)) {
                ObjectNode summary = JsonNodeFactory.instance.objectNode();
                summary.put("source_step", item.path("step").asText());
                summary.put("tool_name", toolName);
                evidence.add(summary);
            }
        }
        return new WorkingContext(request.currentPlan(), history, constraints, evidence);
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
