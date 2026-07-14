package com.agent.javascope.tools.planning;

import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.model.ModelCallException;
import com.agent.javascope.model.ModelRequest;
import com.agent.javascope.model.ModelResult;
import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.contract.plan.FailedStepHistoryItem;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.agent.javascope.tool.contract.ToolOutputContractInspector;
import com.agent.javascope.tool.contract.PlanToolReferenceInspector;
import com.agent.javascope.tool.contract.PlanToolInputContractInspector;
import com.agent.javascope.tool.runtime.PlanSafetyValidator;
import com.agent.javascope.entity.plan.PlanToolData;
import com.agent.javascope.entity.plan.PlanStepFailure;
import com.agent.javascope.entity.plan.PlanStepReplacement;
import com.agent.javascope.entity.plan.RevisePlanRequest;
import com.agent.javascope.entity.tool.ToolResultPayload;
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
import java.util.LinkedHashMap;

public class RevisePlanTool {

    private final AgentPromptProvider promptProvider;
    private final AgentToolExecutor toolExecutor;
    private final AgentChatModelClient agentChatModelClient;
    private final AgentJsonCodecUtil json;
    private final int maxRetry;
    private final PlanSafetyValidator planSafetyValidator;

    public RevisePlanTool(
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient agentChatModelClient,
            AgentJsonCodecUtil json,
            int maxRetry) {
        this(promptProvider, toolExecutor, agentChatModelClient, json, maxRetry, new PlanSafetyValidator() {});
    }

    public RevisePlanTool(
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
                        "failed_steps": {"type": "array", "description": "全部失败或阻塞步骤，含 step_id。"},
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
        List<PlanStepFailure> failedSteps = request.getFailedSteps();
        if (failedSteps.isEmpty() && !failedStep.getStepId().isBlank()) {
            PlanStepFailure fallback = new PlanStepFailure();
            fallback.setStepId(failedStep.getStepId());
            fallback.setStepIndex(failedStepIndex);
            fallback.setStep(failedStep);
            fallback.setActualOutput(failureContext);
            fallback.setReason(reason);
            failedSteps = List.of(fallback);
        }

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
                    failedSteps.stream().map(item -> json.asMap(json.toTree(item))).toList(),
                    completedStepFingerprints,
                    failedStepFingerprints,
                    failedStepHistory,
                    i,
                    lastError,
                    json.toJson(planEligibleToolSchemas()));
            PlanToolData candidate = json.convert(modelContent(agentChatModelClient.chat(new ModelRequest(prompt))), PlanToolData.class);
            List<PlanStepReplacement> replacements = candidate.getReplacements();
            Set<String> existingStepIds = currentPlan.stream()
                    .map(PlanStepDefinition::getStepId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            List<String> errors = validateReplacements(
                    replacements, failedSteps, completedStepFingerprints, failedStepFingerprints, existingStepIds);
            List<PlanStepDefinition> mergedPlan = mergeReplacements(currentPlan, replacements);
            errors.addAll(validateCompletePlan(mergedPlan));
            // 全部使用空步骤替换表示主动放弃失败路径并保守结束，不再要求剩余计划完成原任务。
            // replacement 目标覆盖、残留依赖和完整结构仍由前面的校验保证。
            if (!isAbandonmentRevision(replacements)) {
                errors.addAll(planSafetyValidator.validate(userInput, mergedPlan));
            }
            latest = candidate;
            if (errors.isEmpty()) {
                return json.toJson(ToolResultPayload.success("revise_plan", latest));
            }
            lastError = String.join("; ", errors);
        }
        return json.toJson(ToolResultPayload.failed("revise_plan", List.of(lastError), latest));
    }

    private List<String> validateReplacements(
            List<PlanStepReplacement> replacements,
            List<PlanStepFailure> failedSteps,
            List<String> completedStepFingerprints,
            List<String> failedStepFingerprints,
            Set<String> existingStepIds) {
        List<String> errors = new ArrayList<>();
        Set<String> expectedTargets = new HashSet<>();
        for (PlanStepFailure failed : failedSteps) {
            if (!normalize(failed.getStepId(), "").isEmpty()) expectedTargets.add(failed.getStepId());
        }
        Set<String> actualTargets = new HashSet<>();
        for (PlanStepReplacement replacement : replacements) {
            String target = normalize(replacement.getReplaceStepId(), "");
            if (target.isEmpty() || !expectedTargets.contains(target) || !actualTargets.add(target)) {
                errors.add("replacement target 非法或重复: " + target);
                continue;
            }
            List<PlanStepDefinition> steps = replacement.getSteps();
            errors.addAll(validatePlanStructure(steps, existingStepIds));
            errors.addAll(validateNoPlanningTools(steps));
            errors.addAll(validateNoCompletedStepReuse(steps, completedStepFingerprints));
            errors.addAll(validateNoFailedStepReuse(steps, failedStepFingerprints));
        }
        if (!actualTargets.equals(expectedTargets)) errors.add("replacements 必须覆盖所有失败步骤");
        return errors;
    }

    private List<String> validatePlanStructure(List<PlanStepDefinition> plan, Set<String> existingStepIds) {
        List<String> errors = new ArrayList<>();
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
            }
            if (i == 0 && step.isDependsOnPrevious()) {
                errors.add("plan[0].depends_on_previous 必须为 false");
            }
            for (String dependencyId : step.getDependsOnStepIds()) {
                if ((!knownStepIds.contains(dependencyId) && !existingStepIds.contains(dependencyId))
                        || dependencyId.equals(stepId)) {
                    errors.add("plan[" + i + "].depends_on_step_ids 只能引用当前补丁前序步骤或原计划 step_id: " + dependencyId);
                }
            }
        }
        errors.addAll(PlanToolReferenceInspector.validate(plan, toolExecutor));
        return errors;
    }

    /** replacement 必须先合并回当前计划，再按完整可执行计划重新校验。 */
    private List<PlanStepDefinition> mergeReplacements(
            List<PlanStepDefinition> currentPlan, List<PlanStepReplacement> replacements) {
        Map<String, List<PlanStepDefinition>> patches = new LinkedHashMap<>();
        for (PlanStepReplacement replacement : replacements) {
            if (!normalize(replacement.getReplaceStepId(), "").isEmpty()) {
                patches.put(replacement.getReplaceStepId(), replacement.getSteps());
            }
        }
        List<PlanStepDefinition> merged = new ArrayList<>();
        for (PlanStepDefinition existing : currentPlan) {
            List<PlanStepDefinition> replacement = patches.get(existing.getStepId());
            if (replacement == null) {
                merged.add(existing);
            } else {
                merged.addAll(replacement);
            }
        }
        return merged;
    }

    private List<String> validateCompletePlan(List<PlanStepDefinition> plan) {
        if (plan.isEmpty()) {
            return List.of(); // 删除所有失败路径表示放弃执行，由外层生成保守结论。
        }
        return validatePlanStructure(plan, Set.of());
    }

    private boolean isAbandonmentRevision(List<PlanStepReplacement> replacements) {
        return !replacements.isEmpty()
                && replacements.stream().allMatch(replacement -> replacement.getSteps().isEmpty());
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
        Set<String> banned = Set.of("clarify_requirement", "create_plan", "revise_plan");
        for (int i = 0; i < plan.size(); i++) {
            String tool = normalize(plan.get(i).getTool(), "");
            if (banned.contains(tool)) {
                errors.add("plan[" + i + "].tool 不能是 clarify_requirement/create_plan/revise_plan");
            }
        }
        return errors;
    }

    /** 重规划与首次规划使用相同工具边界，只暴露允许进入计划步骤的工具。 */
    private List<Map<String, Object>> planEligibleToolSchemas() {
        return toolExecutor.listToolSchemas().stream()
                .filter(schema -> {
                    AgentToolDefinition definition =
                            toolExecutor.getToolDefinition(String.valueOf(schema.get("name")));
                    return definition != null && definition.isAllowedInPlanStep();
                })
                .toList();
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
