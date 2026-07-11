package com.agent.javascope.tools.planning;

import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.agent.javascope.entity.plan.PlanToolData;
import com.agent.javascope.entity.tool.ToolResultPayload;
import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.model.ModelCallException;
import com.agent.javascope.model.ModelRequest;
import com.agent.javascope.model.ModelResult;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.tool.annotation.AgentTool;
import com.agent.javascope.tool.annotation.ToolType;
import com.agent.javascope.tool.annotation.ToolVisibility;
import com.agent.javascope.json.AgentJsonCodecUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CreatePlanTool {

    private final AgentPromptProvider promptProvider;
    private final AgentToolExecutor toolExecutor;
    private final AgentChatModelClient agentChatModelClient;
    private final AgentJsonCodecUtil json;
    private final int maxRetry;

    public CreatePlanTool(
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient agentChatModelClient,
            AgentJsonCodecUtil json,
            int maxRetry) {
        this.promptProvider = promptProvider;
        this.toolExecutor = toolExecutor;
        this.agentChatModelClient = agentChatModelClient;
        this.json = json;
        this.maxRetry = maxRetry;
    }

    @AgentTool(
            name = "create_plan",
            title = "创建执行计划",
            description = "当任务为多步、存在依赖或需先检索再汇总时，创建并校验可执行计划。",
            namespace = "system.planning",
            category = "planning",
            tags = {"system", "planning"},
            toolType = ToolType.SYSTEM,
            visibility = ToolVisibility.MODEL_INTERNAL,
            readOnly = true,
            idempotent = false,
            allowedDirectCall = true,
            allowedInPlanStep = false,
            inputSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "user_input": {"type": "string", "description": "用户原始任务，可省略，省略时 runtime 使用本轮原始输入。"},
                        "feedback": {"type": "string", "description": "来自失败校验或重试的额外约束。"}
                      },
                      "required": []
                    }
                    """)
    public String createPlan(Map<String, Object> input, String rawInput) {
        String userInput = normalize((String) input.get("user_input"), rawInput);
        String feedback = normalize((String) input.get("feedback"), "");
        String enriched = feedback.isEmpty() ? userInput : userInput + "\n\n额外约束（来自结果校验失败反馈）: " + feedback;

        String lastError = "计划生成失败";
        PlanToolData latest = new PlanToolData();

        for (int i = 0; i <= maxRetry; i++) {
            String prompt = promptProvider.buildPlanPrompt(
                    enriched,
                    i,
                    lastError,
                    json.toJson(toolExecutor.listToolSchemas()));
            PlanToolData candidate = json.convert(modelContent(agentChatModelClient.chat(new ModelRequest(prompt))), PlanToolData.class);
            List<PlanStepDefinition> plan = candidate.getPlan();
            List<String> errors = validatePlanStructure(plan);

            latest = new PlanToolData(candidate.getTaskUnderstanding(), plan);

            boolean passed = errors.isEmpty();

            if (passed) {
                return json.toJson(ToolResultPayload.success("create_plan", latest));
            }
            lastError = String.join("; ", errors);
        }

        return json.toJson(ToolResultPayload.failed("create_plan", List.of(lastError), latest));
    }

    private List<String> validatePlanStructure(List<PlanStepDefinition> plan) {
        List<String> errors = new ArrayList<>();
        if (plan.isEmpty()) {
            errors.add("plan 不能为空");
            return errors;
        }
        for (int i = 0; i < plan.size(); i++) {
            PlanStepDefinition step = plan.get(i);
            if (normalize(step.getName(), "").isEmpty()) {
                errors.add("plan[" + i + "].name 不能为空");
            }
            if (normalize(step.getDescription(), "").isEmpty()) {
                errors.add("plan[" + i + "].description 不能为空");
            }
            if (normalize(step.getTool(), "").isEmpty()) {
                errors.add("plan[" + i + "].tool 不能为空");
            } else {
                AgentToolDefinition toolDefinition = toolExecutor.getToolDefinition(step.getTool());
                if (toolDefinition == null) {
                    errors.add("plan[" + i + "].tool 未注册: " + step.getTool());
                } else if (!toolDefinition.isAllowedInPlanStep()) {
                    errors.add("plan[" + i + "].tool 不允许作为计划步骤: " + step.getTool());
                }
            }
            if (step.getInput() == null) {
                errors.add("plan[" + i + "].input 必须是对象");
            }
            if (normalize(step.getExpectedOutcome(), "").isEmpty()) {
                errors.add("plan[" + i + "].expected_outcome 不能为空");
            }
            if (i == 0 && step.isDependsOnPrevious()) {
                errors.add("plan[0].depends_on_previous 必须为 false");
            }
        }
        return errors;
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private Object modelContent(ModelResult result) {
        if (result instanceof ModelResult.Success success) {
            return success.content();
        }
        if (result instanceof ModelResult.Failure failure) {
            throw new ModelCallException(failure.error());
        }
        throw new ModelCallException(null);
    }
}
