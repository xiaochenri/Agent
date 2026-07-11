package com.agent.javascope.agent.prompt;

import com.agent.javascope.context.budget.PromptBudget;
import com.agent.javascope.context.projection.WorkingContext;
import com.agent.javascope.prompt.AgentPromptProvider;

import java.util.List;
import java.util.Map;

public interface PromptAssembler {

    String assembleActionPrompt(
            AgentPromptProvider promptProvider,
            String systemInstruction,
            String input,
            String executionMode,
            List<Map<String, Object>> toolSchemas,
            WorkingContext context,
            PromptBudget budget);
}
