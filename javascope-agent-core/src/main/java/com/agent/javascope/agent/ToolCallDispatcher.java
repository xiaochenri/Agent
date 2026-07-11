package com.agent.javascope.agent;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.entity.execution.AgentToolCall;
import com.agent.javascope.entity.plan.PlanRevisionRecord;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.agent.javascope.entity.plan.PlanStepState;
import com.agent.javascope.entity.plan.PlanStepView;
import com.agent.javascope.entity.plan.PlanToolData;
import com.agent.javascope.entity.plan.RevisePlanRequest;
import com.agent.javascope.entity.tool.ToolResultPayload;
import com.agent.javascope.plan.PlanLifecycleEvent;
import com.agent.javascope.plan.PlanStepStatus;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.context.trace.ExecutionEventType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分发模型请求的工具调用，并把计划工具结果接入计划执行器。
 */
class ToolCallDispatcher {

    /** 用于澄清阶段生成约束提示。 */
    private final AgentPromptProvider promptProvider;
    /** 统一工具执行入口。 */
    private final AgentToolExecutor toolExecutor;
    /** JSON 解析、转换和归一化工具。 */
    private final AgentJsonCodecUtil json;
    /** 计划创建/修订后立即执行当前计划。 */
    private final PlanExecutor planExecutor;

    ToolCallDispatcher(
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentJsonCodecUtil json,
            PlanExecutor planExecutor) {
        this.promptProvider = promptProvider;
        this.toolExecutor = toolExecutor;
        this.json = json;
        this.planExecutor = planExecutor;
    }

    /**
     * 顺序执行本轮所有 tool_calls；计划类工具成功后会触发 PlanExecutor。
     */
    ToolDispatchStatus execute(String input, int round, List<AgentToolCall> toolCalls, RuntimeState state) {
        for (AgentToolCall call : toolCalls) {
            String toolName = json.normalize(call.getName(), "");
            AgentToolDefinition definition = toolExecutor.getToolDefinition(toolName);
            if (definition == null) {
                state.executionLog.add(buildToolLog(round, toolName, call.getInput(),
                        buildDirectCallFailure(toolName, "tool not registered")));
                state.riskFlags.add("tool_call_unknown_" + toolName);
                continue;
            }
            if (!definition.isAllowedDirectCall()) {
                state.executionLog.add(buildToolLog(round, toolName, call.getInput(),
                        buildDirectCallFailure(toolName, "tool direct call is not allowed")));
                state.riskFlags.add("tool_call_direct_not_allowed_" + toolName);
                continue;
            }
            Map<String, Object> toolInput = enrichToolInput(toolName, call.getInput(), state);
            state.trace.record(ExecutionEventType.TOOL_REQUESTED, Map.of(
                    "round", round,
                    "tool", toolName,
                    "input", toolInput), Map.of());
            ToolExecutionResult toolResult = toolExecutor.execute(
                    new ToolInvocation(toolName, json.toTree(toolInput), input));
            Map<String, Object> resultJson = json.asMap(json.toTree(toolResult));
            state.trace.record(ExecutionEventType.TOOL_COMPLETED, Map.of(
                    "round", round,
                    "tool", toolName,
                    "input", toolInput), resultJson);
            state.executionLog.add(buildToolLog(round, toolName, toolInput, resultJson));
            recordRevisionInputOutput(toolName, toolInput, resultJson, state);
            if (!isToolSuccess(resultJson)) {
                state.riskFlags.add("tool_call_failed_" + toolName);
                continue;
            }
            if ("clarify_requirement".equals(toolName)) {
                handleClarificationResult(resultJson, state);
                return ToolDispatchStatus.CONTINUE_REASONING;
            }
            applyPlanResult(resultJson, state);
            if (shouldExecutePlanAfterToolCall(toolName, state, resultJson)
                    && !planExecutor.execute(input, round, state)) {
                return ToolDispatchStatus.CONTINUE_REASONING;
            }
        }
        return ToolDispatchStatus.CONTINUE_REASONING;
    }

    private Map<String, Object> buildDirectCallFailure(String toolName, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", toolName);
        result.put("status", "failed");
        result.put("validation_passed", false);
        result.put("validation_rules", List.of());
        result.put("validation_errors", List.of(error));
        result.put("retryable", false);
        result.put("data", null);
        return result;
    }

    /**
     * 处理 clarify_requirement 输出：ask/confirm 会暂停，guess/direct 继续下一轮执行。
     */
    private void handleClarificationResult(Map<String, Object> resultJson, RuntimeState state) {
        state.clarificationData = json.asMap(resultJson.get("data"));
        String clarifyAction = json.normalize((String) state.clarificationData.get("action"), "ask");
        if ("ask".equals(clarifyAction) || "confirm_before_action".equals(clarifyAction)) {
            state.inClarificationStage = true;
            state.validationFeedback = promptProvider.buildClarificationInstruction(state.clarificationData, false);
            return;
        }
        state.inClarificationStage = false;
        state.validationFeedback = buildClarificationDecisionHint(state.clarificationData, clarifyAction);
    }

    /**
     * 给 create_plan/revise_plan 自动补充运行时上下文，降低模型漏传字段的概率。
     */
    private Map<String, Object> enrichToolInput(String toolName, Map<String, Object> originalInput, RuntimeState state) {
        Map<String, Object> enriched = new LinkedHashMap<>(originalInput);
        if ("create_plan".equals(toolName) && !state.validationFeedback.isBlank()) {
            enriched.putIfAbsent("feedback", state.validationFeedback);
        }
        if ("revise_plan".equals(toolName)) {
            String effectiveFeedback = json.normalize(
                    state.validationFeedback,
                    json.normalize(state.lastValidationFeedback, "执行结果与预期不一致"));
            enriched.putIfAbsent("reason", effectiveFeedback);
            enriched.putIfAbsent("current_plan", copyPlan(state.latestPlan));
            enriched.putIfAbsent("failed_step_index", state.lastFailedStepIndex);
            enriched.putIfAbsent("failed_step", buildFailedStepForRevision(state));
            if (!state.lastStepEvaluation.evidence().isEmpty()) {
                enriched.putIfAbsent("failure_context", state.lastStepEvaluation.toMap());
            }
            if (!state.lastFailedPlanFingerprint.isEmpty()) {
                enriched.putIfAbsent("previous_plan_fingerprint", state.lastFailedPlanFingerprint);
            }
            if (!state.completedStepOutputs.isEmpty()) {
                enriched.putIfAbsent("completed_step_fingerprints", new ArrayList<>(state.completedStepOutputs.keySet()));
            }
            if (!state.failedStepHistory.isEmpty()) {
                enriched.putIfAbsent("failed_step_history", new ArrayList<>(state.failedStepHistory.values()));
                enriched.putIfAbsent("failed_step_fingerprints", new ArrayList<>(state.failedStepHistory.keySet()));
            }
        }
        return enriched;
    }

    /**
     * 如果工具返回 plan payload，则更新当前计划并重建可执行步骤状态。
     */
    private void applyPlanResult(Map<String, Object> resultJson, RuntimeState state) {
        ToolResultPayload result = json.convert(resultJson, ToolResultPayload.class);
        if (!hasPlanPayload(result.getData())) {
            return;
        }
        PlanToolData data = json.convert(result.getData(), PlanToolData.class);
        state.latestPlanResult = data;
        List<PlanStepDefinition> plan = data.getPlan();
        String sourceTool = json.normalize(result.getTool(), "");
        if ("revise_plan".equals(sourceTool)) {
            state.latestPlan = mergeRevisionIntoPlan(plan, state, sourceTool);
            state.latestPlanResult.setPlan(state.latestPlan);
            rebuildPlanSteps(state, sourceTool);
        } else if (!plan.isEmpty()) {
            state.latestPlan = mergeRevisionIntoPlan(plan, state, sourceTool);
            state.latestPlanResult.setPlan(state.latestPlan);
            rebuildPlanSteps(state, sourceTool);
        }
    }

    /**
     * 判断工具执行后是否应立即执行计划。
     */
    private boolean shouldExecutePlanAfterToolCall(
            String toolName, RuntimeState state, Map<String, Object> resultJson) {
        if ("create_plan".equals(toolName) || "revise_plan".equals(toolName)) {
            return !state.planSteps.isEmpty();
        }
        ToolResultPayload result = json.convert(resultJson, ToolResultPayload.class);
        if (!hasPlanPayload(result.getData())) {
            return false;
        }
        return !json.convert(result.getData(), PlanToolData.class).getPlan().isEmpty();
    }

    /**
     * 根据最新计划生成 PlanStepState，并标记可复用的已完成步骤。
     */
    private void rebuildPlanSteps(RuntimeState state, String sourceTool) {
        state.planVersion++;
        state.currentPlanId = "plan_v" + state.planVersion;
        state.planSteps.clear();
        for (int i = 0; i < state.latestPlan.size(); i++) {
            PlanStepDefinition raw = state.latestPlan.get(i);
            String stepId = state.currentPlanId + "_step_" + (i + 1);
            String prevId = i == 0 ? null : state.currentPlanId + "_step_" + i;
            String nextId = i == state.latestPlan.size() - 1 ? null : state.currentPlanId + "_step_" + (i + 2);
            state.planSteps.add(new PlanStepState(
                    stepId,
                    json.normalize(raw.getName(), "step_" + (i + 1)),
                    json.normalize(raw.getDescription(), ""),
                    json.normalize(raw.getTool(), ""),
                    raw.getInput(),
                    json.normalize(raw.getExpectedOutcome(), ""),
                    raw.isDependsOnPrevious(),
                    prevId,
                    nextId));
        }
        markReusableCompletedSteps(state);
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("event", PlanLifecycleEvent.PLAN_CREATED.value());
        lifecycle.put("source_tool", sourceTool);
        lifecycle.put("plan_id", state.currentPlanId);
        lifecycle.put("step_count", state.planSteps.size());
        lifecycle.put("steps", buildPlanView(state));
        state.planLifecycle.add(lifecycle);
    }

    /**
     * 构建前端/响应层可读的计划视图。
     */
    private List<PlanStepView> buildPlanView(RuntimeState state) {
        List<PlanStepView> planView = new ArrayList<>();
        for (PlanStepState step : state.planSteps) {
            planView.add(new PlanStepView(step));
        }
        return planView;
    }

    /**
     * 重规划后如果某步骤和已完成步骤指纹一致，则直接标记完成并复用输出。
     */
    private void markReusableCompletedSteps(RuntimeState state) {
        for (PlanStepState step : state.planSteps) {
            String stepFingerprint = fingerprintStep(step.getToolName(), step.getInput());
            Map<String, Object> completedOutput = state.completedStepOutputs.get(stepFingerprint);
            if (completedOutput == null) {
                continue;
            }
            step.setStatus(PlanStepStatus.COMPLETED);
            step.setActualOutput(new LinkedHashMap<>(completedOutput));
        }
    }

    /**
     * 将 revise_plan 返回的新片段合并到旧计划失败位置，保留前序成功步骤和后续非重复步骤。
     */
    private List<PlanStepDefinition> mergeRevisionIntoPlan(
            List<PlanStepDefinition> revisedPlan, RuntimeState state, String sourceTool) {
        if (!"revise_plan".equals(sourceTool) || state.lastFailedStepIndex < 0 || state.latestPlan.isEmpty()) {
            return revisedPlan;
        }
        List<PlanStepDefinition> merged = new ArrayList<>();
        int failedIndex = Math.min(state.lastFailedStepIndex, state.latestPlan.size() - 1);
        List<String> revisedFingerprints = new ArrayList<>();
        for (int i = 0; i < failedIndex; i++) {
            merged.add(copyStep(state.latestPlan.get(i)));
        }
        for (PlanStepDefinition step : revisedPlan) {
            PlanStepDefinition copied = copyStep(step);
            merged.add(copied);
            revisedFingerprints.add(fingerprintStep(copied.getTool(), copied.getInput()));
        }
        for (int i = failedIndex + 1; i < state.latestPlan.size(); i++) {
            PlanStepDefinition copied = copyStep(state.latestPlan.get(i));
            String fingerprint = fingerprintStep(copied.getTool(), copied.getInput());
            if (revisedFingerprints.contains(fingerprint)) {
                continue;
            }
            merged.add(copied);
        }
        return merged;
    }

    /**
     * 构造 revise_plan 所需的失败步骤上下文。
     */
    private PlanStepDefinition buildFailedStepForRevision(RuntimeState state) {
        List<PlanStepDefinition> failedSteps = buildPlanAtIndex(state.latestPlan, state.lastFailedStepIndex);
        return failedSteps.isEmpty() ? new PlanStepDefinition() : failedSteps.get(0);
    }

    /**
     * 深拷贝计划，避免 revise_plan 修改当前计划对象。
     */
    private List<PlanStepDefinition> copyPlan(List<PlanStepDefinition> plan) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        List<PlanStepDefinition> copied = new ArrayList<>();
        for (PlanStepDefinition step : plan) {
            copied.add(copyStep(step));
        }
        return copied;
    }

    /**
     * 取出指定下标的计划步骤副本。
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
     * 复制单个计划步骤。
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
     * 保存 revise_plan 的输入和输出，便于最终响应展示计划修订轨迹。
     */
    private void recordRevisionInputOutput(
            String toolName, Map<String, Object> toolInput, Map<String, Object> resultJson, RuntimeState state) {
        if (!"revise_plan".equals(toolName)) {
            return;
        }
        state.revisedPlan.add(new PlanRevisionRecord(
                json.convert(toolInput, RevisePlanRequest.class),
                json.convert(resultJson, ToolResultPayload.class)));
    }

    /**
     * 生成工具调用日志条目。
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

    /**
     * 工具成功状态判断。
     */
    private boolean isToolSuccess(Map<String, Object> resultJson) {
        return "success".equals(json.normalize((String) resultJson.get("status"), ""));
    }

    /**
     * 判断工具结果 data 是否包含计划结构。
     */
    private boolean hasPlanPayload(Object data) {
        if (!(data instanceof Map<?, ?> raw)) {
            return false;
        }
        return raw.containsKey("plan");
    }

    /**
     * 生成工具步骤指纹，用于复用已完成步骤和去重修订计划。
     */
    private String fingerprintStep(String toolName, Map<String, Object> input) {
        return json.normalize(toolName, "") + "|" + json.toJson(input == null ? Map.of() : input);
    }

    /**
     * 将澄清工具的非暂停决策转成下一轮 reasoning 的提示。
     */
    private String buildClarificationDecisionHint(Map<String, Object> clarificationData, String action) {
        String reasoning = json.normalize((String) clarificationData.get("reasoning"), "");
        String defaultAssumption = json.normalize((String) clarificationData.get("default_assumption"), "");
        if ("execute_with_guess".equals(action)) {
            return "澄清决策=execute_with_guess。"
                    + (reasoning.isBlank() ? "" : "原因：" + reasoning + "。")
                    + (defaultAssumption.isBlank() ? "" : "默认假设：" + defaultAssumption + "。")
                    + "请继续执行并在最终答案中附带轻量确认。";
        }
        return "澄清决策=direct_execute。"
                + (reasoning.isBlank() ? "" : "原因：" + reasoning + "。")
                + "请直接进入后续执行，不要再次发起澄清。";
    }
}
