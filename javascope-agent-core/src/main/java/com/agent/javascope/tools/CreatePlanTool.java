package com.agent.javascope.tools;

import com.agent.javascope.entity.PlanStepDefinition;
import com.agent.javascope.entity.PlanToolData;
import com.agent.javascope.entity.ToolResultPayload;
import com.agent.javascope.entity.AgentToolDefinition;
import com.agent.javascope.chat.AgentChatModelClient;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.spi.AgentTool;
import com.agent.javascope.spi.ToolType;
import com.agent.javascope.spi.ToolVisibility;
import com.agent.javascope.util.AgentJsonCodecUtil;
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
            description = "当任务为多步、存在依赖或需先检索再汇总时，创建并校验1-3步可执行计划。",
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
            PlanToolData candidate = json.convert(json.parseJson(agentChatModelClient.chat(prompt)), PlanToolData.class);
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
        if (plan.size() > 3) {
            errors.add("plan 步数不能超过3");
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
}
