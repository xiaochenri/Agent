package com.agent.javascope.tools;

import com.agent.javascope.chat.AgentChatModelClient;
import com.agent.javascope.entity.AgentToolDefinition;
import com.agent.javascope.entity.FailedStepHistoryItem;
import com.agent.javascope.entity.PlanStepDefinition;
import com.agent.javascope.entity.PlanToolData;
import com.agent.javascope.entity.RevisePlanRequest;
import com.agent.javascope.entity.ToolResultPayload;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.spi.AgentTool;
import com.agent.javascope.spi.ToolType;
import com.agent.javascope.spi.ToolVisibility;
import com.agent.javascope.util.AgentJsonCodecUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RevisePlanTool {

    private final AgentPromptProvider promptProvider;
    private final AgentToolExecutor toolExecutor;
    private final AgentChatModelClient agentChatModelClient;
    private final AgentJsonCodecUtil json;
    private final int maxRetry;

    public RevisePlanTool(
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
            name = "revise_plan",
            title = "修正执行计划",
            description = "当计划执行失败、依赖阻塞或结果与预期不一致时，基于执行偏差修正计划。",
            namespace = "system.planning",
            category = "planning",
            tags = {"system", "planning", "replan"},
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
                        "user_input": {"type": "string", "description": "用户原始任务，可省略。"},
                        "reason": {"type": "string", "description": "触发重规划的原因。"},
                        "current_plan": {"type": "array", "description": "当前计划步骤数组。"},
                        "failed_step_index": {"type": "integer", "description": "失败步骤索引。"},
                        "failed_step": {"type": "object", "description": "失败步骤定义。"},
                        "failure_context": {"type": "object", "description": "失败工具输出和校验上下文。"}
                      },
                      "required": []
                    }
                    """)
    public String revisePlan(Map<String, Object> input, String rawInput) {
        RevisePlanRequest request = json.convert(input, RevisePlanRequest.class);
        String userInput = normalize(request.getUserInput(), rawInput);
        String reason = normalize(request.getReason(), "执行结果与预期不一致");
        List<PlanStepDefinition> currentPlan = request.getCurrentPlan();
        String currentPlanFingerprint = fingerprintPlan(currentPlan);
        Map<String, Object> failureContext = request.getFailureContext();
        int failedStepIndex = request.getFailedStepIndex();
        PlanStepDefinition failedStep = request.getFailedStep();
        String previousPlanFingerprint = normalize(request.getPreviousPlanFingerprint(), "");
        List<String> completedStepFingerprints = request.getCompletedStepFingerprints();
        List<String> failedStepFingerprints = request.getFailedStepFingerprints();
        List<FailedStepHistoryItem> failedStepHistory = request.getFailedStepHistory();

        String lastError = "计划修正失败";
        PlanToolData latest = new PlanToolData();
        for (int i = 0; i <= maxRetry; i++) {
            String prompt = promptProvider.buildRevisePlanPrompt(
                    userInput,
                    reason,
                    currentPlan,
                    failedStepIndex,
                    failedStep,
                    failureContext,
                    completedStepFingerprints,
                    failedStepFingerprints,
                    failedStepHistory,
                    i,
                    lastError,
                    json.toJson(toolExecutor.listToolSchemas()));
            PlanToolData candidate = json.convert(json.parseJson(agentChatModelClient.chat(prompt)), PlanToolData.class);
            List<PlanStepDefinition> plan = candidate.getPlan();
            List<String> errors = validatePlanStructure(plan);
            String candidateFingerprint = fingerprintPlan(plan);
            if (!currentPlanFingerprint.isEmpty() && currentPlanFingerprint.equals(candidateFingerprint)) {
                errors.add("新计划与当前计划重复，必须调整步骤、工具或入参");
            }
            if (!previousPlanFingerprint.isEmpty() && previousPlanFingerprint.equals(candidateFingerprint)) {
                errors.add("新计划与上一轮失败计划重复，必须生成不同的工具入参组合");
            }
            errors.addAll(validateNoPlanningTools(plan));
            errors.addAll(validateNoCompletedStepReuse(plan, completedStepFingerprints));
            errors.addAll(validateNoFailedStepReuse(plan, failedStepFingerprints));

            latest = new PlanToolData(candidate.getTaskUnderstanding(), plan);
            if (errors.isEmpty()) {
                return json.toJson(ToolResultPayload.success("revise_plan", latest));
            }
            lastError = String.join("; ", errors);
        }
        return json.toJson(ToolResultPayload.failed("revise_plan", List.of(lastError), latest));
    }

    private List<String> validatePlanStructure(List<PlanStepDefinition> plan) {
        List<String> errors = new ArrayList<>();
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

    private String fingerprintPlan(List<PlanStepDefinition> plan) {
        if (plan.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (PlanStepDefinition step : plan) {
            parts.add(fingerprintPlanStep(step));
        }
        return String.join("->", parts);
    }

    private List<String> validateNoPlanningTools(List<PlanStepDefinition> plan) {
        List<String> errors = new ArrayList<>();
        Set<String> banned = Set.of("create_plan", "revise_plan");
        for (int i = 0; i < plan.size(); i++) {
            String tool = normalize(plan.get(i).getTool(), "");
            if (banned.contains(tool)) {
                errors.add("plan[" + i + "].tool 不能是 create_plan/revise_plan");
            }
        }
        return errors;
    }

    private List<String> validateNoCompletedStepReuse(
            List<PlanStepDefinition> plan, List<String> completedFingerprints) {
        List<String> errors = new ArrayList<>();
        if (completedFingerprints.isEmpty()) {
            return errors;
        }
        Set<String> completed = new HashSet<>(completedFingerprints);
        for (int i = 0; i < plan.size(); i++) {
            if (completed.contains(fingerprintPlanStep(plan.get(i)))) {
                errors.add("plan[" + i + "] 与已成功步骤重复，不应重新规划");
            }
        }
        return errors;
    }

    private List<String> validateNoFailedStepReuse(List<PlanStepDefinition> plan, List<String> failedFingerprints) {
        List<String> errors = new ArrayList<>();
        if (failedFingerprints.isEmpty()) {
            return errors;
        }
        Set<String> failed = new HashSet<>(failedFingerprints);
        for (int i = 0; i < plan.size(); i++) {
            if (failed.contains(fingerprintPlanStep(plan.get(i)))) {
                errors.add("plan[" + i + "] 与历史失败步骤重复，不应再次规划相同工具入参组合");
            }
        }
        return errors;
    }

    private String fingerprintPlanStep(PlanStepDefinition step) {
        return normalize(step.getTool(), "") + "|" + json.toJson(step.getInput());
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
