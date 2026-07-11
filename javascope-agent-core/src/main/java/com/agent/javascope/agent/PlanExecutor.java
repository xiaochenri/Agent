package com.agent.javascope.agent;

import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.contract.plan.FailedStepHistoryItem;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.agent.javascope.entity.plan.PlanStepState;
import com.agent.javascope.plan.PlanLifecycleEvent;
import com.agent.javascope.plan.PlanStepStatus;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.tools.validation.StepValidatorTool;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.context.trace.ExecutionEventType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 顺序执行当前计划，并负责步骤状态、步骤校验和失败重试控制。
 */
class PlanExecutor {

    /** 读取 planMaxRetry 等运行时配置。 */
    private final AgentRuntimeProperties properties;
    /** 计划步骤最终通过统一工具执行器调用。 */
    private final AgentToolExecutor toolExecutor;
    /** JSON 解析、序列化和字符串归一化工具。 */
    private final AgentJsonCodecUtil json;
    /** 对单个计划步骤的实际输出进行语义校验。 */
    private final StepValidatorTool stepValidatorTool;

    PlanExecutor(
            AgentRuntimeProperties properties,
            AgentToolExecutor toolExecutor,
            AgentJsonCodecUtil json,
            StepValidatorTool stepValidatorTool) {
        this.properties = properties;
        this.toolExecutor = toolExecutor;
        this.json = json;
        this.stepValidatorTool = stepValidatorTool;
    }

    /**
     * 执行 state.planSteps 中尚未完成的步骤；失败时写入 validationFeedback 交给下一轮重规划。
     */
    boolean execute(String input, int round, RuntimeState state) {
        if (state.planSteps.isEmpty()) {
            state.riskFlags.add("plan_empty");
            state.validationFeedback = "计划为空，无法执行。请判断是否需要调用 revise_plan 重新生成计划。";
            return false;
        }
        List<String> failureReasons = new ArrayList<>();
        for (int i = 0; i < state.planSteps.size(); i++) {
            PlanStepState step = state.planSteps.get(i);
            if (step.getStatus() == PlanStepStatus.COMPLETED) {
                state.planLifecycle.add(Map.of(
                        "event", PlanLifecycleEvent.STEP_COMPLETED.value(),
                        "step_id", step.getStepId(),
                        "step_name", step.getName(),
                        "reused", true,
                        "actual_output", step.getActualOutput()));
                continue;
            }
            if (step.isDependsOnPrevious()
                    && step.getPreviousStepId() != null
                    && !isPreviousStepCompleted(state.planSteps, step.getPreviousStepId())) {
                String reason = "前置步骤未完成，不能继续执行: " + step.getStepId();
                recordPreExecutionFailure(state, i, step, reason, "plan_dependency_blocked_" + step.getStepId());
                failureReasons.add(reason);
                continue;
            }
            String toolName = step.getToolName();
            if (toolName.isEmpty()) {
                String reason = "计划步骤缺少 tool: step=" + i;
                recordPreExecutionFailure(state, i, step, reason, "plan_step_tool_missing_" + i);
                failureReasons.add(reason);
                continue;
            }
            AgentToolDefinition toolDefinition = toolExecutor.getToolDefinition(toolName);
            if (toolDefinition == null) {
                String reason = "计划步骤使用了未注册工具: " + toolName;
                recordPreExecutionFailure(state, i, step, reason, "plan_step_tool_unknown_" + i);
                failureReasons.add(reason);
                continue;
            }
            if (!toolDefinition.isAllowedInPlanStep()) {
                String reason = "工具 " + toolName + " 不允许作为计划执行步骤";
                recordPreExecutionFailure(state, i, step, reason, "plan_step_tool_not_allowed_" + toolName);
                failureReasons.add(reason);
                continue;
            }
            step.setStatus(PlanStepStatus.IN_PROGRESS);
            state.planLifecycle.add(Map.of(
                    "event", PlanLifecycleEvent.STEP_STARTED.value(),
                    "step_id", step.getStepId(),
                    "step_name", step.getName(),
                    "depends_on_previous", step.isDependsOnPrevious(),
                    "previous_step_id", step.getPreviousStepId() == null ? "" : step.getPreviousStepId(),
                    "next_step_id", step.getNextStepId() == null ? "" : step.getNextStepId()));
            Map<String, Object> toolInput = PlanInputResolver.resolve(step.getInput(), i, state.planSteps);
            state.trace.record(ExecutionEventType.TOOL_REQUESTED, Map.of(
                    "round", round,
                    "tool", toolName,
                    "input", toolInput,
                    "plan_step", step.getStepId()), Map.of());
            ToolExecutionResult toolResult = toolExecutor.execute(
                    new ToolInvocation(toolName, json.toTree(toolInput), input));
            Map<String, Object> resultJson = ToolExecutionResultMapper.toCompatibilityMap(toolResult, json);
            state.trace.record(ExecutionEventType.TOOL_COMPLETED, Map.of(
                    "round", round,
                    "tool", toolName,
                    "plan_step", step.getStepId()), resultJson);
            state.executionLog.add(buildToolLog(round, toolName, toolInput, resultJson));
            String expectedOutcome = step.getExpectedOutcome();
            if (!toolResult.isSuccess()) {
                step.setStatus(PlanStepStatus.FAILED);
                step.setActualOutput(resultJson);
                rememberFailedStep(state, step, resultJson, "工具执行失败");
                state.lastStepEvaluation = stepValidatorTool.buildToolExecutionFailedResult(resultJson);
                state.lastFailedStepIndex = i;
                state.lastFailedPlanFingerprint = fingerprintPlan(buildPlanAtIndex(state.latestPlan, i));
                String reason = "计划步骤执行失败: step=" + step.getName() + "；实际=" + json.toJson(resultJson);
                state.riskFlags.add("plan_step_failed_" + i);
                state.planLifecycle.add(Map.of(
                        "event", PlanLifecycleEvent.STEP_FAILED.value(),
                        "step_id", step.getStepId(),
                        "step_name", step.getName(),
                        "reason", "工具执行失败",
                        "actual_output", resultJson));
                shouldSkipFailedStep(state, i, step, reason);
                failureReasons.add(reason);
                continue;
            }
            StepValidatorTool.StepEvaluationResult evaluation = stepValidatorTool.evaluateStepOutcome(step, resultJson);
            state.lastStepEvaluation = evaluation;
            state.executionLog.add(stepValidatorTool.buildStepValidationLog(round, step, evaluation));
            if (!evaluation.passed()) {
                step.setStatus(PlanStepStatus.FAILED);
                step.setActualOutput(resultJson);
                rememberFailedStep(state, step, resultJson, "结果未达预期");
                String reason = "计划步骤执行结果未达到预期成果: step=" + step.getName();
                state.riskFlags.add("plan_expected_mismatch_" + i);
                state.lastFailedStepIndex = i;
                state.lastFailedPlanFingerprint = fingerprintPlan(buildPlanAtIndex(state.latestPlan, i));
                String detailedReason = reason
                        + "；failure_code=" + evaluation.failureCode().code()
                        + "（" + evaluation.failureCode().descriptionZh() + "）"
                        + "；failure_reasons=" + String.join("|", evaluation.reasons())
                        + "；预期=" + expectedOutcome
                        + "；实际=" + json.toJson(resultJson);
                state.planLifecycle.add(Map.of(
                        "event", PlanLifecycleEvent.STEP_FAILED_EXPECTED_MISMATCH.value(),
                        "step_id", step.getStepId(),
                        "step_name", step.getName(),
                        "failure_code", evaluation.failureCode().code(),
                        "failure_code_desc", evaluation.failureCode().descriptionZh(),
                        "failure_reasons", evaluation.reasons(),
                        "score", evaluation.score(),
                        "expected_outcome", expectedOutcome,
                        "actual_output", resultJson));
                shouldSkipFailedStep(state, i, step, detailedReason);
                failureReasons.add(detailedReason);
                continue;
            }
            step.setStatus(PlanStepStatus.COMPLETED);
            step.setActualOutput(resultJson);
            rememberCompletedStep(state, step, resultJson);
            state.planLifecycle.add(Map.of(
                    "event", PlanLifecycleEvent.STEP_COMPLETED.value(),
                    "step_id", step.getStepId(),
                    "step_name", step.getName(),
                    "actual_output", resultJson));
        }
        if (!failureReasons.isEmpty()) {
            state.validationFeedback = failureReasons.get(failureReasons.size() - 1)
                    + "。其他不依赖失败步骤的计划步骤已继续执行；请调用 revise_plan 补齐失败部分。";
            state.ephemeralMemory.add(buildPlanExecutionSummary(state));
            return false;
        }
        state.lastFailedStepIndex = -1;
        state.lastFailedPlanFingerprint = "";
        state.lastStepEvaluation = stepValidatorTool.buildPassResult(1.0, new LinkedHashMap<>());
        state.planLifecycle.add(Map.of("event", PlanLifecycleEvent.PLAN_COMPLETED.value(), "plan_id", state.currentPlanId));
        state.ephemeralMemory.add(buildPlanExecutionSummary(state));
        return true;
    }

    /**
     * 生成工具调用日志，保持与 Agent 基类里的工具日志格式一致。
     */
    private AgentExecutionLogEntry buildToolLog(
            int round, String toolName, Map<String, Object> toolInput, Map<String, Object> resultJson) {
        return new AgentExecutionLogEntry(
                "tool_call_round_" + round,
                toolName,
                toolInput,
                resultJson,
                "success".equals(resultJson.get("status")) ? 0.9 : 0.3);
    }

    /** 将执行前校验或依赖失败落为步骤失败，但不阻断后续独立步骤。 */
    private void recordPreExecutionFailure(
            RuntimeState state, int stepIndex, PlanStepState step, String reason, String riskFlag) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("tool", step.getToolName());
        failure.put("status", "failed");
        failure.put("validation_passed", false);
        failure.put("validation_rules", List.of());
        failure.put("validation_errors", List.of(reason));
        failure.put("retryable", false);
        failure.put("error_code", "plan_precondition_failed");
        failure.put("data", null);
        failure.put("metadata", Map.of());
        step.setStatus(PlanStepStatus.FAILED);
        step.setActualOutput(failure);
        rememberFailedStep(state, step, failure, reason);
        state.lastStepEvaluation = stepValidatorTool.buildToolExecutionFailedResult(failure);
        state.lastFailedStepIndex = stepIndex;
        state.lastFailedPlanFingerprint = fingerprintPlan(buildPlanAtIndex(state.latestPlan, stepIndex));
        state.riskFlags.add(riskFlag);
        state.planLifecycle.add(Map.of(
                "event", PlanLifecycleEvent.STEP_FAILED.value(),
                "step_id", step.getStepId(),
                "step_name", step.getName(),
                "reason", reason,
                "actual_output", failure));
    }

    /**
     * 依赖型步骤只允许在前置步骤完成后执行。
     */
    private boolean isPreviousStepCompleted(List<PlanStepState> steps, String stepId) {
        for (PlanStepState step : steps) {
            if (step.getStepId().equals(stepId)) {
                return PlanStepStatus.COMPLETED == step.getStatus();
            }
        }
        return false;
    }

    /**
     * 缓存成功步骤输出，重规划生成相同步骤时可直接复用。
     */
    private void rememberCompletedStep(RuntimeState state, PlanStepState step, Map<String, Object> output) {
        String stepFingerprint = fingerprintStep(step.getToolName(), step.getInput());
        state.completedStepOutputs.put(stepFingerprint, new LinkedHashMap<>(output));
    }

    /**
     * 记录失败步骤，供 revise_plan 避免重复失败的工具和参数组合。
     */
    private void rememberFailedStep(RuntimeState state, PlanStepState step, Map<String, Object> output, String reason) {
        String stepFingerprint = fingerprintStep(step.getToolName(), step.getInput());
        state.failedStepHistory.put(stepFingerprint, new FailedStepHistoryItem(
                step.getName(),
                step.getToolName(),
                new LinkedHashMap<>(step.getInput()),
                reason,
                new LinkedHashMap<>(output)));
    }

    /**
     * 同一工具+输入超过最大重试次数后放弃当前步骤，继续后续可执行步骤。
     */
    private boolean shouldSkipFailedStep(RuntimeState state, int stepIndex, PlanStepState step, String reason) {
        String retryKey = fingerprintStep(step.getToolName(), step.getInput());
        int retryCount = state.stepRetryCounts.getOrDefault(retryKey, 0) + 1;
        state.stepRetryCounts.put(retryKey, retryCount);
        if (retryCount <= properties.getPlanMaxRetry()) {
            return false;
        }
        state.riskFlags.add("plan_step_retry_exhausted_" + stepIndex);
        state.validationFeedback = "";
        state.planLifecycle.add(Map.of(
                "event", "step_retry_exhausted",
                "step_index", stepIndex,
                "step_retry_key", retryKey,
                "retry_count", retryCount,
                "max_retry", properties.getPlanMaxRetry(),
                "reason", reason));
        return true;
    }

    /**
     * 计划终态摘要会进入下一轮短期记忆，约束最终答案覆盖成功和失败步骤。
     */
    private String buildPlanExecutionSummary(RuntimeState state) {
        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (PlanStepState step : state.planSteps) {
            if (step.getStatus() == PlanStepStatus.COMPLETED) {
                succeeded.add(step.getName());
            } else if (step.getStatus() == PlanStepStatus.FAILED) {
                failed.add(step.getName());
            }
        }
        return "计划执行已结束。成功步骤: "
                + (succeeded.isEmpty() ? "无" : String.join("、", succeeded))
                + "；失败/放弃步骤: "
                + (failed.isEmpty() ? "无" : String.join("、", failed))
                + "。最终回复必须结合成功步骤与失败步骤总结。";
    }

    /**
     * 使用工具名和输入生成计划指纹，用于识别重复计划。
     */
    private String fingerprintPlan(List<PlanStepDefinition> plan) {
        if (plan == null || plan.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (PlanStepDefinition step : plan) {
            parts.add(fingerprintStep(step.getTool(), step.getInput()));
        }
        return String.join("->", parts);
    }

    /**
     * 取出失败位置对应的原始计划步骤，作为 revise_plan 的失败上下文。
     */
    private List<PlanStepDefinition> buildPlanAtIndex(List<PlanStepDefinition> plan, int index) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        if (index < 0 || index >= plan.size()) {
            return List.of();
        }
        return List.of(copyStep(plan.get(index)));
    }

    /**
     * 深拷贝计划步骤，避免修订合并时修改原始计划对象。
     */
    private PlanStepDefinition copyStep(PlanStepDefinition step) {
        return new PlanStepDefinition(
                step.getName(),
                step.getDescription(),
                step.getTool(),
                new LinkedHashMap<>(step.getInput()),
                step.getExpectedOutcome(),
                step.isDependsOnPrevious());
    }

    /**
     * 单步骤指纹，缓存成功/失败步骤时使用。
     */
    private String fingerprintStep(String toolName, Map<String, Object> input) {
        return json.normalize(toolName, "") + "|" + json.toJson(input == null ? Map.of() : input);
    }
}
