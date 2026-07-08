package com.agent.javascope.agent;

import com.agent.javascope.chat.AgentChatModelClient;
import com.agent.javascope.config.AgentRuntimeProperties;
import com.agent.javascope.entity.AgentExecutionLogEntry;
import com.agent.javascope.entity.AgentToolCall;
import com.agent.javascope.entity.FailedStepHistoryItem;
import com.agent.javascope.entity.PlanRevisionRecord;
import com.agent.javascope.entity.PlanStepDefinition;
import com.agent.javascope.entity.PlanStepState;
import com.agent.javascope.entity.PlanStepView;
import com.agent.javascope.entity.PlanToolData;
import com.agent.javascope.entity.RouteDecision;
import com.agent.javascope.entity.RevisePlanRequest;
import com.agent.javascope.entity.ToolResultPayload;
import com.agent.javascope.enums.PlanLifecycleEvent;
import com.agent.javascope.enums.PlanStepStatus;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.tools.AgentToolExecutor;
import com.agent.javascope.tools.StepValidatorTool;
import com.agent.javascope.verifier.IndependentVerifierService;
import com.agent.javascope.verifier.VerifierCheck;
import com.agent.javascope.verifier.VerifierEvidence;
import com.agent.javascope.verifier.VerifierNextAction;
import com.agent.javascope.verifier.VerifierResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.agent.javascope.util.AgentJsonCodecUtil;
import org.springframework.stereotype.Component;

@Component
public class ReActAgent extends Agent {

    // 临时开关：按需求先关闭 validate_result_round 阶段。
    private static final boolean DISABLE_RESULT_VALIDATION_ROUND = true;

    private final StepValidatorTool stepValidatorTool;
    private final IndependentVerifierService independentVerifierService;

    public ReActAgent(
            AgentRuntimeProperties properties,
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json,
            StepValidatorTool stepValidatorTool,
            IndependentVerifierService independentVerifierService) {
        super(properties, promptProvider, toolExecutor, modelClient, json);
        this.stepValidatorTool = stepValidatorTool;
        this.independentVerifierService = independentVerifierService;
    }

    public String call(String input, String sessionId, String userId) {
        RuntimeState state = execute(input);
        return respondAsText(
                state.latestPlanResult,
                buildPlanView(state),
                state.executionLog,
                state.revisedPlan,
                state.planLifecycle,
                state.blockedReason,
                state.lastFinalAnswer,
                state.riskFlags);
    }

    public String callStream(String input, String sessionId, String userId, Consumer<String> chunkConsumer) {
        RuntimeState state = execute(input);
        return respondAsStream(
                state.latestPlanResult,
                buildPlanView(state),
                state.executionLog,
                state.revisedPlan,
                state.planLifecycle,
                state.blockedReason,
                state.lastFinalAnswer,
                state.riskFlags,
                chunkConsumer);
    }

    private RuntimeState execute(String input) {
        RuntimeState state = new RuntimeState();
        RouteDecision routeDecision = routeInput(input, state);
        state.routeDecision = routeDecision;
        if (!"task".equals(routeDecision.getRoute())) {
            state.lastFinalAnswer = buildDirectRouteFinalAnswer(input, routeDecision, state);
            state.blockedReason = blockedReason(state.lastFinalAnswer, state.executionLog);
            return state;
        }

        for (int round = 1; round <= properties.getMaxRounds(); round++) {
            state.lastResponse = reasoning(input, round, state);
            List<AgentToolCall> toolCalls = extractToolCalls(state.lastResponse);
            if (state.inClarificationStage) {
                if (!toolCalls.isEmpty()) {
                    state.riskFlags.add("clarification_stage_tool_call_blocked");
                    state.validationFeedback = promptProvider.buildClarificationInstruction(state.clarificationData, true);
                    continue;
                }
                state.lastFinalAnswer = json.asMap(state.lastResponse.get("final_answer"));
                if (state.lastFinalAnswer == null || state.lastFinalAnswer.isEmpty()) {
                    state.lastFinalAnswer = buildClarificationFinalAnswerFallback(state.clarificationData);
                }
                state.blockedReason = "等待用户确认澄清信息";
                state.riskFlags.add("awaiting_user_clarification");
                return state;
            }
            if (!toolCalls.isEmpty()) {
                boolean shouldContinue = executeToolCalls(input, round, toolCalls, state);
                if (!shouldContinue) {
                    return state;
                }
                continue;
            }

            state.lastFinalAnswer = json.asMap(state.lastResponse.get("final_answer"));
            if (DISABLE_RESULT_VALIDATION_ROUND) {
                if (state.lastFinalAnswer == null || state.lastFinalAnswer.isEmpty()) {
                    state.lastFinalAnswer = buildFallbackFinalAnswer(input, state);
                }
                state.blockedReason = blockedReason(state.lastFinalAnswer, state.executionLog);
                return state;
            }
            state.lastValidation = validateFinalAnswer(input, state.latestPlan, state.executionLog, state.lastFinalAnswer);
            if (state.lastValidation.passed()) {
                state.blockedReason = blockedReason(state.lastFinalAnswer, state.executionLog);
                return state;
            }
            handleValidationFailure(round, state);
        }

        if (tryFinalSynthesis(input, state)) {
            state.blockedReason = blockedReason(state.lastFinalAnswer, state.executionLog);
            return state;
        }

        state.blockedReason = "结果验证连续失败: " + String.join("; ", state.lastValidation.reasons());
        state.riskFlags.add("result_validation_exhausted");
        if (state.lastFinalAnswer == null || state.lastFinalAnswer.isEmpty()) {
            state.lastFinalAnswer = buildFallbackFinalAnswer(input, state);
        }
        return state;
    }

    private RouteDecision routeInput(String input, RuntimeState state) {
        ensureAgentInitialized();
        String prompt = promptProvider.buildRoutePrompt(systemInstruction, input);
        Map<String, Object> raw = json.parseJson(chatModel(prompt));
        String route = normalizeRoute(raw.get("route"));
        double confidence = normalizeConfidence(raw.get("confidence"));
        String reason = json.normalize(raw.get("reason") == null ? "" : String.valueOf(raw.get("reason")), "");
        String rawRoute = raw.get("route") == null ? "" : String.valueOf(raw.get("route"));
        if (!route.equals(json.normalize(rawRoute, "").toLowerCase())) {
            state.riskFlags.add("route_normalized_to_" + route);
        }
        if (confidence < 0.5) {
            state.riskFlags.add("route_low_confidence");
        }
        RouteDecision decision = new RouteDecision(route, confidence, reason);
        Map<String, Object> routeOutput = new LinkedHashMap<>();
        routeOutput.put("route", route);
        routeOutput.put("confidence", confidence);
        routeOutput.put("reason", reason);
        state.executionLog.add(new AgentExecutionLogEntry(
                "route_decision",
                "intent_router",
                Map.of("user_input", input),
                routeOutput,
                confidence));
        return decision;
    }

    private double normalizeConfidence(Object confidenceObj) {
        if (confidenceObj instanceof Number number) {
            double value = number.doubleValue();
            return Math.max(0.0, Math.min(1.0, value));
        }
        if (confidenceObj instanceof String text) {
            try {
                double value = Double.parseDouble(text.trim());
                return Math.max(0.0, Math.min(1.0, value));
            } catch (NumberFormatException ignored) {
                return 0.5;
            }
        }
        return 0.5;
    }

    private String normalizeRoute(Object routeObj) {
        String normalized = json.normalize(routeObj == null ? "" : String.valueOf(routeObj), "task")
                .toLowerCase();
        if ("chat".equals(normalized) || "meta".equals(normalized) || "task".equals(normalized)) {
            return normalized;
        }
        return "task";
    }

    private Map<String, Object> buildDirectRouteFinalAnswer(String input, RouteDecision routeDecision, RuntimeState state) {
        String prompt = promptProvider.buildDirectReplyPrompt(
                systemInstruction,
                input,
                routeDecision.getRoute(),
                routeDecision.getReason());
        Map<String, Object> response = json.parseJson(chatModel(prompt));
        state.executionLog.add(new AgentExecutionLogEntry(
                "direct_reply",
                "direct_reply_module",
                Map.of(
                        "route", routeDecision.getRoute(),
                        "route_reason", routeDecision.getReason(),
                        "user_input", input),
                response,
                0.7));
        List<AgentToolCall> toolCalls = extractToolCalls(response);
        if (!toolCalls.isEmpty()) {
            state.riskFlags.add("direct_reply_tool_call_blocked");
        }
        Map<String, Object> finalAnswer = json.asMap(response.get("final_answer"));
        if (finalAnswer == null || finalAnswer.isEmpty()) {
            return buildDirectRouteFinalAnswerFallback(routeDecision);
        }
        return finalAnswer;
    }

    private Map<String, Object> reasoning(String input, int round, RuntimeState state) {
        String hint = buildPlanHint(state.latestPlan, state.validationFeedback);
        if (!hint.isEmpty()) {
            state.ephemeralMemory.add(hint);
        }
        String memoryJson = json.toJson(buildReasoningMemory(state.executionLog, state.ephemeralMemory));
        ensureAgentInitialized();
        String prompt = promptProvider.buildActionPrompt(
                systemInstruction,
                input,
                memoryJson,
                toolSchemasJson,
                json.toJson(state.latestPlan),
                json.toJson(state.executionLog),
                json.normalize(state.validationFeedback, "无"));
        Map<String, Object> response = json.parseJson(chatModel(prompt));
        state.executionLog.add(buildReasoningLog(input, round, state.validationFeedback, response));
        state.lastValidationFeedback = state.validationFeedback;
        state.validationFeedback = "";
        state.ephemeralMemory.clear();
        return response;
    }

    private String buildPlanHint(List<PlanStepDefinition> latestPlan, String validationFeedback) {
        if (latestPlan == null || latestPlan.isEmpty()) {
            return "";
        }
        return "当前计划提示: " + json.toJson(latestPlan) + "；最近校验反馈: " + json.normalize(validationFeedback, "无");
    }

    private List<Map<String, Object>> buildReasoningMemory(
            List<AgentExecutionLogEntry> executionLog, List<String> ephemeralMemory) {
        List<Map<String, Object>> memory = new ArrayList<>();
        int start = Math.max(0, executionLog.size() - 3);
        for (int i = start; i < executionLog.size(); i++) {
            memory.add(Map.of("type", "execution_log", "content", executionLog.get(i)));
        }
        for (String hint : ephemeralMemory) {
            memory.add(Map.of("type", "hint", "content", hint));
        }
        return memory;
    }

    private List<AgentToolCall> extractToolCalls(Map<String, Object> response) {
        List<AgentToolCall> result = new ArrayList<>();
        // 从模型输出中提取 tool_calls，统一规范成 {name,input} 结构，避免主流程处理脏数据。
        Object toolCallsObj = response.get("tool_calls");
        if (!(toolCallsObj instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            Map<String, Object> call = json.asMap(item);
            if (json.normalize((String) call.get("name"), "").isEmpty()) {
                continue;
            }
            result.add(new AgentToolCall(
                    json.normalize((String) call.get("name"), ""),
                    json.asMap(call.get("input"))));
        }
        return result;
    }

    private boolean executeToolCalls(String input, int round, List<AgentToolCall> toolCalls, RuntimeState state) {
        for (AgentToolCall call : toolCalls) {
            String toolName = json.normalize(call.getName(), "");
            Map<String, Object> toolInput = enrichToolInput(toolName, call.getInput(), state);
            String toolResult = toolExecutor.execute(toolName, toolInput, input);
            Map<String, Object> resultJson = json.parseJson(toolResult);
            state.executionLog.add(buildToolLog(round, toolName, toolInput, resultJson));
            recordRevisionInputOutput(toolName, toolInput, resultJson, state);
            if (!isToolSuccess(resultJson)) {
                state.riskFlags.add("tool_call_failed_" + toolName);
                continue;
            }
            if ("clarify_requirement".equals(toolName)) {
                state.clarificationData = json.asMap(resultJson.get("data"));
                String clarifyAction = json.normalize((String) state.clarificationData.get("action"), "ask");
                if ("ask".equals(clarifyAction)) {
                    state.inClarificationStage = true;
                    state.validationFeedback = promptProvider.buildClarificationInstruction(state.clarificationData, false);
                    return true;
                }
                state.inClarificationStage = false;
                state.validationFeedback = buildClarificationDecisionHint(state.clarificationData, clarifyAction);
                return true;
            }
            applyPlanResult(resultJson, state);
            if (shouldExecutePlanAfterToolCall(toolName, state, resultJson)) {
                if (!executePlan(input, round, state)) {
                    return true;
                }
            }
        }
        return true;
    }

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

    private boolean isToolSuccess(Map<String, Object> resultJson) {
        return "success".equals(json.normalize((String) resultJson.get("status"), ""));
    }

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

    private boolean hasPlanPayload(Object data) {
        if (!(data instanceof Map<?, ?> raw)) {
            return false;
        }
        return raw.containsKey("plan");
    }

    private boolean executePlan(String input, int round, RuntimeState state) {
        if (state.planSteps.isEmpty()) {
            state.riskFlags.add("plan_empty");
            state.validationFeedback = "计划为空，无法执行。请判断是否需要调用 revise_plan 重新生成计划。";
            return false;
        }
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
                state.riskFlags.add("plan_dependency_blocked_" + step.getStepId());
                state.validationFeedback = "前置步骤未完成，不能继续执行: " + step.getStepId()
                        + "。请基于执行日志判断下一步，必要时调用 revise_plan。";
                return false;
            }
            String toolName = step.getToolName();
            if (toolName.isEmpty()) {
                state.riskFlags.add("plan_step_tool_missing_" + i);
                state.validationFeedback = "计划步骤缺少 tool: step=" + i
                        + "。请基于当前计划判断是否调用 revise_plan 修正计划。";
                return false;
            }
            step.setStatus(PlanStepStatus.IN_PROGRESS);
            state.planLifecycle.add(Map.of(
                    "event", PlanLifecycleEvent.STEP_STARTED.value(),
                    "step_id", step.getStepId(),
                    "step_name", step.getName(),
                    "depends_on_previous", step.isDependsOnPrevious(),
                    "previous_step_id", step.getPreviousStepId() == null ? "" : step.getPreviousStepId(),
                    "next_step_id", step.getNextStepId() == null ? "" : step.getNextStepId()));
            Map<String, Object> toolInput = step.getInput();
            String toolResult = toolExecutor.execute(toolName, toolInput, input);
            Map<String, Object> resultJson = json.parseJson(toolResult);
            state.executionLog.add(buildToolLog(round, toolName, toolInput, resultJson));
            String expectedOutcome = step.getExpectedOutcome();
            if (!isToolSuccess(resultJson)) {
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
                if (shouldSkipFailedStep(state, i, step, reason)) {
                    continue;
                }
                state.validationFeedback = reason + "。请调用 revise_plan，基于当前计划与错误上下文重规划。";
                return false;
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
                if (shouldSkipFailedStep(state, i, step, detailedReason)) {
                    continue;
                }
                state.validationFeedback = detailedReason + "。请调用 revise_plan，基于当前计划与错误上下文重规划。";
                return false;
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
        state.lastFailedStepIndex = -1;
        state.lastFailedPlanFingerprint = "";
        state.lastStepEvaluation = stepValidatorTool.buildPassResult(1.0, new LinkedHashMap<>());
        state.planLifecycle.add(Map.of("event", PlanLifecycleEvent.PLAN_COMPLETED.value(), "plan_id", state.currentPlanId));
        state.ephemeralMemory.add(buildPlanExecutionSummary(state));
        return true;
    }

    private boolean isPreviousStepCompleted(List<PlanStepState> steps, String stepId) {
        for (PlanStepState step : steps) {
            if (step.getStepId().equals(stepId)) {
                return PlanStepStatus.COMPLETED == step.getStatus();
            }
        }
        return false;
    }

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

    private List<PlanStepView> buildPlanView(RuntimeState state) {
        List<PlanStepView> planView = new ArrayList<>();
        for (PlanStepState step : state.planSteps) {
            planView.add(new PlanStepView(step));
        }
        return planView;
    }

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

    private PlanStepDefinition buildFailedStepForRevision(RuntimeState state) {
        List<PlanStepDefinition> failedSteps = buildPlanAtIndex(state.latestPlan, state.lastFailedStepIndex);
        return failedSteps.isEmpty() ? new PlanStepDefinition() : failedSteps.get(0);
    }

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

    private List<PlanStepDefinition> buildPlanAtIndex(List<PlanStepDefinition> plan, int index) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        if (index < 0 || index >= plan.size()) {
            return List.of();
        }
        return List.of(copyStep(plan.get(index)));
    }

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

    private PlanStepDefinition copyStep(PlanStepDefinition step) {
        return new PlanStepDefinition(
                step.getName(),
                step.getDescription(),
                step.getTool(),
                new LinkedHashMap<>(step.getInput()),
                step.getExpectedOutcome(),
                step.isDependsOnPrevious());
    }

    private void rememberCompletedStep(RuntimeState state, PlanStepState step, Map<String, Object> output) {
        String stepFingerprint = fingerprintStep(step.getToolName(), step.getInput());
        state.completedStepOutputs.put(stepFingerprint, new LinkedHashMap<>(output));
    }

    private void rememberFailedStep(RuntimeState state, PlanStepState step, Map<String, Object> output, String reason) {
        String stepFingerprint = fingerprintStep(step.getToolName(), step.getInput());
        state.failedStepHistory.put(stepFingerprint, new FailedStepHistoryItem(
                step.getName(),
                step.getToolName(),
                new LinkedHashMap<>(step.getInput()),
                reason,
                new LinkedHashMap<>(output)));
    }

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

    private String fingerprintStep(String toolName, Map<String, Object> input) {
        return json.normalize(toolName, "") + "|" + json.toJson(input == null ? Map.of() : input);
    }

    private void recordRevisionInputOutput(
            String toolName, Map<String, Object> toolInput, Map<String, Object> resultJson, RuntimeState state) {
        if (!"revise_plan".equals(toolName)) {
            return;
        }
        state.revisedPlan.add(new PlanRevisionRecord(
                json.convert(toolInput, RevisePlanRequest.class),
                json.convert(resultJson, ToolResultPayload.class)));
    }

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

    private ValidationResult validateFinalAnswer(
            String input,
            List<PlanStepDefinition> plan,
            List<AgentExecutionLogEntry> executionLog,
            Map<String, Object> finalAnswer) {
        VerifierResult result = independentVerifierService.verify(input, plan, executionLog, finalAnswer);
        List<String> reasons = new ArrayList<>();
        for (VerifierCheck check : result.getChecks()) {
            if (!"fail".equals(check.getResult())) {
                continue;
            }
            reasons.add(check.getId() + ":" + check.getReason());
        }
        if (reasons.isEmpty() && "fail".equals(result.getVerdict())) {
            reasons = List.of(json.normalize(result.getSummary(), "独立验证器判定失败"));
        }
        boolean passed = "pass".equals(result.getVerdict());
        VerifierNextAction nextAction = result.getNextAction();
        boolean suggestReplan = !passed && !"none".equals(json.normalize(nextAction.getCategory(), "none"));
        return new ValidationResult(
                passed,
                reasons,
                suggestReplan,
                result.getSummary(),
                result.getChecks(),
                result.getEvidence(),
                result.getWarnings(),
                result.getNextAction());
    }

    private void handleValidationFailure(int round, RuntimeState state) {
        state.validationFeedback = buildValidationFeedback(state.lastValidation);
        state.riskFlags.add("final_answer_validation_failed_round_" + round);
        if (state.lastValidation.suggestReplan()) {
            state.riskFlags.add("final_answer_suggest_replan_round_" + round);
        }
        Map<String, Object> validationOutput = new LinkedHashMap<>();
        validationOutput.put("passed", false);
        validationOutput.put("summary", state.lastValidation.summary());
        validationOutput.put("reasons", state.lastValidation.reasons());
        validationOutput.put("suggest_replan", state.lastValidation.suggestReplan());
        validationOutput.put("checks", state.lastValidation.checks());
        validationOutput.put("evidence", state.lastValidation.evidence());
        validationOutput.put("warnings", state.lastValidation.warnings());
        validationOutput.put("next_action", state.lastValidation.nextAction());
        state.executionLog.add(new AgentExecutionLogEntry(
                "validate_result_round_" + round,
                "independent_verifier",
                Map.of("final_answer", state.lastFinalAnswer),
                validationOutput,
                0.2));
    }

    private String buildValidationFeedback(ValidationResult validation) {
        List<String> feedback = new ArrayList<>(validation.reasons());
        if (!validation.summary().isBlank()) {
            feedback.add("summary=" + validation.summary());
        }
        if (validation.nextAction() != null
                && !json.normalize(validation.nextAction().getInstruction(), "").isBlank()) {
            feedback.add("next_action=" + validation.nextAction().getInstruction());
        }
        feedback.add("suggest_replan=" + validation.suggestReplan());
        if (validation.suggestReplan()) {
            feedback.add("请优先调用 revise_plan 修正当前计划；若当前没有计划，请调用 create_plan 补齐证据");
            feedback.add("key_evidence 可为总结表达，不要求逐字引用工具原文；但必须可映射到 execution_log，建议补充 key_evidence_refs");
        }
        return String.join("; ", feedback);
    }

    private String blockedReason(Map<String, Object> finalAnswer, List<AgentExecutionLogEntry> executionLog) {
        if (finalAnswer == null || finalAnswer.isEmpty()) {
            return "最终答案为空";
        }
        boolean hasToolResult = executionLog.stream()
                .map(item -> normalizeStatus(json.asMap(item.getOutput()).get("status")))
                .anyMatch(status -> !status.isEmpty());
        if (!hasToolResult) {
            return "";
        }
        boolean anySuccess = executionLog.stream()
                .map(item -> normalizeStatus(json.asMap(item.getOutput()).get("status")))
                .anyMatch("success"::equals);
        if (!anySuccess) {
            return "所有工具步骤均失败，无法继续给出高置信结论";
        }
        return "";
    }

    private String normalizeStatus(Object status) {
        return status == null ? "" : String.valueOf(status).trim();
    }

    private Map<String, Object> buildFallbackFinalAnswer(String input, RuntimeState state) {
        List<String> conclusions = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> nextActions = new ArrayList<>();

        conclusions.add("当前推理轮次已用尽，暂时无法完成全部验证。建议下一步二选一：1）补充关键信息后继续；2）按保守默认策略先给出初步结论与风险清单。");

        for (AgentExecutionLogEntry log : state.executionLog) {
            String toolName = json.normalize(log.getToolName(), "");
            if ("reasoning".equals(toolName)
                    || "result_verifier".equals(toolName)
                    || "independent_verifier".equals(toolName)
                    || StepValidatorTool.TOOL_NAME.equals(toolName)) {
                continue;
            }
            Map<String, Object> output = json.asMap(log.getOutput());
            String status = json.normalize((String) output.get("status"), "");
            if ("success".equals(status)) {
                evidence.add("工具 " + toolName + " 执行成功，输出摘要: " + summarizeOutput(output));
            } else if ("failed".equals(status)) {
                risks.add("工具 " + toolName + " 执行失败: " + summarizeOutput(output));
            }
        }

        if (evidence.isEmpty()) {
            evidence.add("当前轮次未获得可直接复用的高置信工具结果。");
        }
        if (risks.isEmpty()) {
            risks.add("结果未经完整验证，结论可能不完整。");
        }
        if (state.blockedReason != null && !state.blockedReason.isBlank()) {
            risks.add(state.blockedReason);
        }
        nextActions.add("选项1：补充关键信息（如股票代码、时间范围、分析维度）后重试。");
        nextActions.add("选项2：在现有证据下按保守策略输出初步结论，并明确不确定性。");

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("core_conclusions", conclusions);
        fallback.put("key_evidence", evidence);
        fallback.put("risk_points", risks);
        fallback.put("next_actions", nextActions);
        fallback.put("fallback", true);
        fallback.put("original_question", input);
        return fallback;
    }

    private Map<String, Object> buildDirectRouteFinalAnswerFallback(RouteDecision routeDecision) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        String route = routeDecision.getRoute();
        if ("meta".equals(route)) {
            fallback.put("core_conclusions", List.of("我是任务型助手，可协助分析、检索、规划并给出结构化结论。"));
            fallback.put("key_evidence", List.of("当前问题被识别为 meta 问题，按直答模式返回。"));
            fallback.put("risk_points", List.of("未进入工具链，回复基于通用能力说明。"));
            fallback.put("next_actions", List.of("你可以直接给出任务目标、对象和时间范围，我将继续处理。"));
        } else {
            fallback.put("core_conclusions", List.of("收到你的消息。"));
            fallback.put("key_evidence", List.of("当前问题被识别为 chat 闲聊场景，按直答模式返回。"));
            fallback.put("risk_points", List.of("未进入任务工具链，不涉及外部检索结果。"));
            fallback.put("next_actions", List.of("如需我执行任务，请直接描述目标和约束条件。"));
        }
        fallback.put("route", route);
        fallback.put("fallback", true);
        return fallback;
    }

    private Map<String, Object> buildClarificationFinalAnswerFallback(Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("core_conclusions", List.of("关键信息缺失，已暂停后续执行，等待你补充后继续。"));
        List<String> missingFields = json.asStringList(data.get("missing_fields"));
        output.put("key_evidence", missingFields.isEmpty()
                ? List.of("缺失字段未识别，请补充分析对象、时间范围和约束。")
                : List.of("缺失信息：" + String.join("、", missingFields)));
        output.put("risk_points", List.of("在缺少关键信息时继续执行会产生错误结论风险。"));

        List<String> nextActions = new ArrayList<>();
        nextActions.add("请先补充缺失信息后再继续。");
        output.put("next_actions", nextActions);
        output.put("awaiting_clarification", true);
        return output;
    }

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

    private String summarizeOutput(Map<String, Object> output) {
        Map<String, Object> data = json.asMap(output.get("data"));
        if (!data.isEmpty()) {
            return json.toJson(data);
        }
        List<String> errors = json.asStringList(output.get("validation_errors"));
        if (!errors.isEmpty()) {
            return String.join("; ", errors);
        }
        return json.toJson(output);
    }

    private boolean tryFinalSynthesis(String input, RuntimeState state) {
        if (!isPlanTerminal(state)) {
            return false;
        }
        state.validationFeedback = "计划执行已结束。请基于全部执行日志输出 final_answer，不要继续调用工具。";
        state.lastResponse = reasoning(input, properties.getMaxRounds() + 1, state);
        List<AgentToolCall> toolCalls = extractToolCalls(state.lastResponse);
        if (!toolCalls.isEmpty()) {
            state.riskFlags.add("final_synthesis_tool_call_unexpected");
            return false;
        }
        state.lastFinalAnswer = json.asMap(state.lastResponse.get("final_answer"));
        state.lastValidation = validateFinalAnswer(input, state.latestPlan, state.executionLog, state.lastFinalAnswer);
        if (!state.lastValidation.passed()) {
            handleValidationFailure(properties.getMaxRounds() + 1, state);
            return false;
        }
        return true;
    }

    private boolean isPlanTerminal(RuntimeState state) {
        if (state.planSteps.isEmpty()) {
            return false;
        }
        for (PlanStepState step : state.planSteps) {
            if (step.getStatus() == PlanStepStatus.PENDING || step.getStatus() == PlanStepStatus.IN_PROGRESS) {
                return false;
            }
        }
        return true;
    }

    private record ValidationResult(
            boolean passed,
            List<String> reasons,
            boolean suggestReplan,
            String summary,
            List<VerifierCheck> checks,
            List<VerifierEvidence> evidence,
            List<String> warnings,
            VerifierNextAction nextAction) {
        private ValidationResult(boolean passed, List<String> reasons, boolean suggestReplan) {
            this(
                    passed,
                    reasons,
                    suggestReplan,
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    new VerifierNextAction());
        }
    }

    private static final class RuntimeState {
        // 全链路执行日志：记录每轮推理、工具调用与校验结果，最终原样返回给上层。
        private final List<AgentExecutionLogEntry> executionLog = new ArrayList<>();
        // 计划修订轨迹：保存 create_plan/revise_plan 的多次尝试结果，便于追踪计划如何演进。
        private final List<PlanRevisionRecord> revisedPlan = new ArrayList<>();
        // 计划生命周期事件：记录计划创建、步骤开始/完成/失败等状态流转，用于过程审计。
        private final List<Map<String, Object>> planLifecycle = new ArrayList<>();
        // 轮间短期记忆：仅在下一次 reasoning 提示词中使用，调用后会清空。
        private final List<String> ephemeralMemory = new ArrayList<>();
        // 当前可执行计划的结构化步骤列表，供 executePlan 顺序执行与依赖校验。
        private final List<PlanStepState> planSteps = new ArrayList<>();
        // 最近一次计划工具返回的完整结果（含 task_understanding 等），用于最终响应聚合。
        private PlanToolData latestPlanResult = new PlanToolData();
        // 最近生效的原始计划（Map 结构），用于构建步骤视图和后续推理提示。
        private List<PlanStepDefinition> latestPlan = new ArrayList<>();
        // 风险标记集合：沉淀执行过程中的异常/阻塞信号，作为最终响应的风险输出。
        private final List<String> riskFlags = new ArrayList<>();
        // 校验反馈文本：在结果验证失败后写入，下一轮作为修正提示引导模型重试。
        private String validationFeedback = "";
        // 最近一次推理前的校验反馈，避免 reasoning 清空后 revise_plan 丢失失败原因。
        private String lastValidationFeedback = "";
        // 最近一次最终答案校验结果：用于判断是否通过、是否建议重规划及失败原因。
        private ValidationResult lastValidation = new ValidationResult(false, List.of("未开始验证"), false);
        // 最近一次模型给出的 final_answer，供验证与最终返回使用。
        private Map<String, Object> lastFinalAnswer = new LinkedHashMap<>();
        // 前置路由结果：记录 chat/meta/task 分类结论，便于审计和后续分支控制。
        private RouteDecision routeDecision = new RouteDecision();
        // 最近一次模型原始响应（含 tool_calls/final_answer），用于当前轮分支决策。
        private Map<String, Object> lastResponse = new LinkedHashMap<>();
        // 最近一次步骤结果评估，供 revise_plan 使用失败上下文。
        private StepValidatorTool.StepEvaluationResult lastStepEvaluation =
                StepValidatorTool.StepEvaluationResult.pass(1.0, new LinkedHashMap<>());
        // 最近一次执行失败时的计划指纹，避免 revise_plan 返回同构计划。
        private String lastFailedPlanFingerprint = "";
        // 最近一次执行失败的步骤下标，用于只重规划当前失败步骤。
        private int lastFailedStepIndex = -1;
        // 按计划链位置记录失败重试次数，超过 planMaxRetry 后放弃当前步骤并继续后续步骤。
        private final Map<String, Integer> stepRetryCounts = new LinkedHashMap<>();
        // 已成功执行步骤缓存（tool+input 指纹 -> 输出），用于重规划后跳过重复步骤。
        private final Map<String, Map<String, Object>> completedStepOutputs = new LinkedHashMap<>();
        // 历史失败步骤缓存（tool+input 指纹 -> 失败详情），用于 revise_plan 避免重复失败工具组合。
        private final Map<String, FailedStepHistoryItem> failedStepHistory = new LinkedHashMap<>();
        // 计划版本号：每次重建计划自增，用于生成稳定且可追踪的 plan_id。
        private int planVersion = 0;
        // 当前计划标识（如 plan_v1），用于串联步骤 ID 与生命周期日志。
        private String currentPlanId = "";
        // 执行结束时的阻塞原因，由 execute 统一写入，交由回复层输出。
        private String blockedReason = "";
        // 澄清阶段标记：已调用 clarify_requirement，下一轮必须由模型按固定格式输出澄清回复。
        private boolean inClarificationStage = false;
        // 澄清工具返回的结构化上下文，供模型生成澄清回复时参考。
        private Map<String, Object> clarificationData = new LinkedHashMap<>();
    }
}
