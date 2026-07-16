package com.agent.javascope.agent.prompt;

import com.agent.javascope.context.budget.PromptBudget;
import com.agent.javascope.context.projection.WorkingContext;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.prompt.AgentPromptProvider;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** 统一渲染工作上下文，并在超出字符预算时按区域裁剪低优先级内容。 */
public class DefaultPromptAssembler implements PromptAssembler {

    private final AgentJsonCodecUtil json;

    public DefaultPromptAssembler(AgentJsonCodecUtil json) {
        this.json = json;
    }

    @Override
    public String assembleActionPrompt(
            AgentPromptProvider promptProvider,
            String systemInstruction,
            String input,
            String executionMode,
            List<Map<String, Object>> toolSchemas,
            WorkingContext context,
            PromptBudget budget) {
        String prompt = render(promptProvider, systemInstruction, input, executionMode, toolSchemas,
                context.currentPlan(), context.relevantHistory(), context.activeConstraints(),
                context.latestObservations(), context.evidenceSummaries(), context.activeToolFailures());
        if (withinBudget(prompt, budget)) return prompt;

        // 低优先级区域逐级清空；每次都重新渲染完整 Prompt，禁止按字符位置截断。
        prompt = render(promptProvider, systemInstruction, input, executionMode, toolSchemas,
                context.currentPlan(), JsonNodeFactory.instance.arrayNode(), context.activeConstraints(),
                context.latestObservations(), context.evidenceSummaries(), context.activeToolFailures());
        if (withinBudget(prompt, budget)) return prompt;

        prompt = render(promptProvider, systemInstruction, input, executionMode, toolSchemas,
                context.currentPlan(), JsonNodeFactory.instance.arrayNode(), context.activeConstraints(),
                context.latestObservations(), JsonNodeFactory.instance.arrayNode(),
                context.activeToolFailures());
        if (withinBudget(prompt, budget)) return prompt;

        prompt = render(promptProvider, systemInstruction, input, executionMode, compactToolSchemas(toolSchemas),
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
                context.activeConstraints(), context.latestObservations(),
                JsonNodeFactory.instance.arrayNode(), context.activeToolFailures());
        if (withinBudget(prompt, budget)) return prompt;

        // mandatory 区域本身超预算时宁可显式超预算，也不能删除系统规则、活跃失败或约束。
        return prompt + "\n\n[强制恢复上下文超过字符预算；为保证错误可达性未做字符串截断。]";
    }

    private String render(
            AgentPromptProvider promptProvider,
            String systemInstruction,
            String input,
            String executionMode,
            List<Map<String, Object>> toolSchemas,
            Object currentPlan,
            Object relevantHistory,
            Object activeConstraints,
            Object latestObservations,
            Object evidenceSummaries,
            Object activeToolFailures) {
        Map<String, Object> evidenceContext = new LinkedHashMap<>();
        // 活跃失败先于普通观察呈现，并且任何裁剪阶段都不会删除。
        evidenceContext.put("active_tool_failures", activeToolFailures);
        evidenceContext.put("latest_tool_observations", latestObservations);
        evidenceContext.put("evidence_summaries", evidenceSummaries);
        return promptProvider.buildActionPrompt(
                systemInstruction,
                input,
                executionMode,
                json.toJson(evidenceContext),
                json.toJson(toolSchemas),
                json.toJson(currentPlan),
                json.toJson(relevantHistory),
                json.toJson(activeConstraints));
    }

    private boolean withinBudget(String prompt, PromptBudget budget) {
        return prompt.length() <= budget.maxCharacters();
    }

    /** 超预算时仅保留工具恢复决策所需的名称、类型和输入契约。 */
    private List<Map<String, Object>> compactToolSchemas(List<Map<String, Object>> toolSchemas) {
        return toolSchemas.stream().map(schema -> {
            Map<String, Object> compact = new LinkedHashMap<>();
            for (String field : List.of("name", "tool_type", "input_schema")) {
                if (schema.containsKey(field)) compact.put(field, schema.get(field));
            }
            return compact;
        }).toList();
    }
}
