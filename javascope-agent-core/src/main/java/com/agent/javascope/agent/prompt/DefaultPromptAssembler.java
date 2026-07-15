package com.agent.javascope.agent.prompt;

import com.agent.javascope.context.budget.PromptBudget;
import com.agent.javascope.context.projection.WorkingContext;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.prompt.AgentPromptProvider;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统一渲染工作上下文，并在超出字符预算时裁剪低优先级历史。 */
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
        Map<String, Object> evidenceContext = new LinkedHashMap<>();
        evidenceContext.put("investigation_state", context.investigationState());
        evidenceContext.put("latest_tool_observations", context.latestObservations());
        evidenceContext.put("evidence_summaries", context.evidenceSummaries());
        String prompt = promptProvider.buildActionPrompt(
                systemInstruction,
                input,
                executionMode,
                json.toJson(evidenceContext),
                json.toJson(toolSchemas),
                json.toJson(context.currentPlan()),
                json.toJson(context.relevantHistory()),
                json.toJson(context.activeConstraints()));
        if (prompt.length() <= budget.maxCharacters()) {
            return prompt;
        }
        return prompt.substring(0, budget.maxCharacters())
                + "\n\n[上下文已按预算裁剪；完整执行过程保存在 execution trace 中。]";
    }
}
