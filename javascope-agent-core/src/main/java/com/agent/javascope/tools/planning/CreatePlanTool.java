package com.agent.javascope.tools.planning;

import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.tool.runtime.PlanSafetyValidator;
import com.agent.javascope.tool.contract.ToolOutputContractInspector;
import com.agent.javascope.tool.contract.PlanToolReferenceInspector;
import com.agent.javascope.tool.contract.PlanToolInputContractInspector;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CreatePlanTool {

    private final AgentPromptProvider promptProvider;
    private final AgentToolExecutor toolExecutor;
    private final AgentChatModelClient agentChatModelClient;
    private final AgentJsonCodecUtil json;
    private final int maxRetry;
    /** 业务侧校验关键参数来源，阻止模型凭空假设执行对象。 */
    private final PlanSafetyValidator planSafetyValidator;

    public CreatePlanTool(
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient agentChatModelClient,
            AgentJsonCodecUtil json,
            int maxRetry) {
        this(promptProvider, toolExecutor, agentChatModelClient, json, maxRetry, new PlanSafetyValidator() {});
    }

    public CreatePlanTool(
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient agentChatModelClient,
            AgentJsonCodecUtil json,
            int maxRetry,
            PlanSafetyValidator planSafetyValidator) {
        this.promptProvider = promptProvider;
        this.toolExecutor = toolExecutor;
        this.agentChatModelClient = agentChatModelClient;
        this.json = json;
        this.maxRetry = maxRetry;
        this.planSafetyValidator = planSafetyValidator == null ? new PlanSafetyValidator() {} : planSafetyValidator;
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
        // 原始运行时输入是安全边界，不能允许模型通过 tool input 替换用户任务来注入虚假关键对象。
        String userInput = normalize(rawInput, normalize((String) input.get("user_input"), ""));
        String feedback = normalize((String) input.get("feedback"), "");
        String enriched = feedback.isEmpty() ? userInput : userInput + "\n\n额外约束（来自结果校验失败反馈）: " + feedback;

        String lastError = "计划生成失败";
        PlanToolData latest = new PlanToolData();

        for (int i = 0; i <= maxRetry; i++) {
            String prompt = promptProvider.buildPlanPrompt(
                    enriched,
                    i,
                    lastError,
                    json.toJson(planEligibleToolSchemas()));
            PlanToolData candidate = json.convert(modelContent(agentChatModelClient.chat(new ModelRequest(prompt))), PlanToolData.class);
            List<PlanStepDefinition> plan = candidate.getPlan();
            List<String> errors = validatePlanStructure(plan);
            List<String> safetyErrors = planSafetyValidator.validate(userInput, plan);
            errors.addAll(safetyErrors);

            latest = new PlanToolData(candidate.getTaskUnderstanding(), plan);

            boolean passed = errors.isEmpty();

            if (passed) {
                return json.toJson(ToolResultPayload.success("create_plan", latest));
            }
            lastError = String.join("; ", errors);
            // P0 参数来源不可信时继续让规划器猜测只会放大风险，立即返回外层模型进入澄清。
            if (!safetyErrors.isEmpty()) {
                return json.toJson(ToolResultPayload.failed("create_plan", List.of(lastError), latest));
            }
        }

        return json.toJson(ToolResultPayload.failed("create_plan", List.of(lastError), latest));
    }

    private List<String> validatePlanStructure(List<PlanStepDefinition> plan) {
        List<String> errors = new ArrayList<>();
        if (plan.isEmpty()) {
            errors.add("plan 不能为空");
            return errors;
        }
        Set<String> knownStepIds = new HashSet<>();
        for (int i = 0; i < plan.size(); i++) {
            PlanStepDefinition step = plan.get(i);
            String stepId = normalize(step.getStepId(), "");
            if (stepId.isEmpty() || !knownStepIds.add(stepId)) {
                errors.add("plan[" + i + "].step_id 不能为空且必须唯一");
            }
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
                } else {
                    errors.addAll(PlanToolInputContractInspector.validate(
                            toolDefinition, step.getInput(), "plan[" + i + "]"));
                    errors.addAll(ToolOutputContractInspector.validate(
                            toolDefinition, step.getRequiredOutputs(), "plan[" + i + "]"));
                }
            }
            if (step.getInput() == null) {
                errors.add("plan[" + i + "].input 必须是对象");
            }
            if (normalize(step.getExpectedOutcome(), "").isEmpty()) {
                errors.add("plan[" + i + "].expected_outcome 不能为空");
            }
            if (step.getRequiredOutputs().isEmpty()) {
                errors.add("plan[" + i + "].required_outputs 不能为空");
            } else {
                for (int outputIndex = 0; outputIndex < step.getRequiredOutputs().size(); outputIndex++) {
                    if (normalize(step.getRequiredOutputs().get(outputIndex).getPath(), "").isEmpty()) {
                        errors.add("plan[" + i + "].required_outputs[" + outputIndex + "].path 不能为空");
                    }
                }
            }
            if (i == 0 && step.isDependsOnPrevious()) {
                errors.add("plan[0].depends_on_previous 必须为 false");
            }
            for (String dependencyId : step.getDependsOnStepIds()) {
                if (!knownStepIds.contains(dependencyId) || dependencyId.equals(stepId)) {
                    errors.add("plan[" + i + "].depends_on_step_ids 只能引用已定义的前序 step_id: " + dependencyId);
                }
            }
        }
        errors.addAll(PlanToolReferenceInspector.validate(plan, toolExecutor));
        return errors;
    }

    /**
     * 规划模型只应看到可作为 PlanStep 的业务工具，避免再次把澄清、建计划或重规划工具写入计划。
     */
    private List<Map<String, Object>> planEligibleToolSchemas() {
        return toolExecutor.listToolSchemas().stream()
                .filter(schema -> {
                    String toolName = String.valueOf(schema.get("name"));
                    AgentToolDefinition definition = toolExecutor.getToolDefinition(toolName);
                    return definition != null && definition.isAllowedInPlanStep();
                })
                .toList();
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
