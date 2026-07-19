package com.agent.javascope.agent.strategy;

import com.agent.javascope.agent.finalization.FinalAnswerSynthesizer;
import com.agent.javascope.agent.planning.PlanExecutor;
import com.agent.javascope.agent.planning.ToolCallDispatcher;
import com.agent.javascope.agent.planning.ToolDispatchStatus;
import com.agent.javascope.agent.prompt.PromptAssembler;
import com.agent.javascope.agent.routing.AgentToolCallExtractor;
import com.agent.javascope.agent.runtime.Agent;
import com.agent.javascope.agent.runtime.InvestigationStateTracker;
import com.agent.javascope.agent.runtime.RuntimeState;
import com.agent.javascope.agent.runtime.ToolFailureRecord;
import com.agent.javascope.agent.runtime.ToolFailureTracker;
import com.agent.javascope.context.budget.PromptBudget;
import com.agent.javascope.context.projection.ContextManager;
import com.agent.javascope.context.projection.ContextRequest;
import com.agent.javascope.context.projection.WorkingContext;
import com.agent.javascope.context.trace.ExecutionEventType;
import com.agent.javascope.context.trace.ExecutionLogStore;
import com.agent.javascope.entity.execution.AgentToolCall;
import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.tools.validation.StepValidatorTool;
import com.agent.javascope.verifier.IndependentVerifierService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * direct、react 和 planned 任务共用的单一 Action/Observation 循环。
 * 子类只定义路由支持范围和当前模式可见的工具，避免为不同模式复制一套 ReAct 内核。
 */
abstract class AbstractToolLoopExecutionStrategy extends Agent implements ExecutionStrategy {

    private static final int MAX_TOOL_LOOP_ROUNDS = 10;

    private final ToolCallDispatcher toolCallDispatcher;
    private final FinalAnswerSynthesizer finalAnswerSynthesizer;
    private final AgentToolCallExtractor toolCallExtractor;
    private final InvestigationStateTracker investigationStateTracker;
    private final ContextManager contextManager;
    private final PromptAssembler promptAssembler;

    protected AbstractToolLoopExecutionStrategy(
            AgentRuntimeProperties properties,
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json,
            boolean planningEnabled,
            StepValidatorTool stepValidatorTool,
            IndependentVerifierService independentVerifierService,
            ExecutionLogStore executionLogStore,
            ContextManager contextManager,
            PromptAssembler promptAssembler) {
        super(properties, promptProvider, toolExecutor, modelClient, json);
        this.toolCallExtractor = new AgentToolCallExtractor(json);
        this.investigationStateTracker = new InvestigationStateTracker(json);
        this.finalAnswerSynthesizer =
                new FinalAnswerSynthesizer(properties, json, independentVerifierService, toolCallExtractor);
        this.toolCallDispatcher = planningEnabled
                ? new ToolCallDispatcher(
                        promptProvider, toolExecutor, json,
                        new PlanExecutor(properties, toolExecutor, json, stepValidatorTool))
                : new ToolCallDispatcher(promptProvider, toolExecutor, json);
        this.contextManager = contextManager;
        this.promptAssembler = promptAssembler;
    }

    /** 返回当前执行模式允许模型看到的工具；运行时仍会对越权工具做二次校验。 */
    protected abstract List<Map<String, Object>> visibleToolSchemas(RuntimeState state);

    @Override
    public RuntimeState execute(ExecutionRequest request) {
        String input = request.input();
        RuntimeState state = request.state();
        Consumer<Map<String, Object>> eventConsumer = request.eventConsumer();
        boolean useModelStream = request.useModelStream();
        int emittedPlanLifecycleCount = 0;
        ensureAgentInitialized();
        for (int round = 1; round <= MAX_TOOL_LOOP_ROUNDS; round++) {
            emitStreamEvent(eventConsumer, "process", "第 " + round + " 轮：模型分析下一步动作");
            state.lastResponse = reasoning(input, round, state, eventConsumer, useModelStream);
            if ("react".equals(state.routeDecision.getExecutionMode()) && !state.inClarificationStage) {
                InvestigationStateTracker.UpdateResult updateResult =
                        investigationStateTracker.apply(state, state.lastResponse, round);
                if (!updateResult.valid()) {
                    state.riskFlags.add("react_reasoning_update_invalid");
                    state.validationFeedback = String.join("；", updateResult.errors());
                    emitReasoningValidation(eventConsumer, round, updateResult.errors());
                    continue;
                }
                rememberInvestigationWarnings(state, updateResult);
                emitReasoningUpdate(eventConsumer, round, state.lastResponse, state, updateResult.warnings());
            }
            List<AgentToolCall> toolCalls = toolCallExtractor.extract(state.lastResponse);
            if (state.inClarificationStage) {
                if (!toolCalls.isEmpty()) {
                    state.riskFlags.add("clarification_stage_tool_call_blocked");
                    state.validationFeedback = promptProvider.buildClarificationInstruction(state.clarificationData, true);
                    continue;
                }
                state.lastFinalAnswer = json.asMap(state.lastResponse.get("final_answer"));
                if (state.lastFinalAnswer == null || state.lastFinalAnswer.isEmpty()) {
                    state.lastFinalAnswer =
                            finalAnswerSynthesizer.buildClarificationFinalAnswerFallback(state.clarificationData);
                }
                state.blockedReason = "等待用户确认澄清信息";
                state.riskFlags.add("awaiting_user_clarification");
                return state;
            }
            if (!toolCalls.isEmpty()) {
                toolCalls = constrainAdaptiveActions(toolCalls, state);
                if (toolCalls.isEmpty()) {
                    continue;
                }
                emitToolCalls(eventConsumer, toolCalls);
                int executionLogStart = state.executionLog.size();
                ToolDispatchStatus dispatchStatus = toolCallDispatcher.execute(input, round, toolCalls, state);
                emitToolObservations(eventConsumer, round, state, executionLogStart);
                if (state.inClarificationStage) {
                    emitClarificationEvent(eventConsumer, state);
                }
                emittedPlanLifecycleCount = emitPlanLifecycle(eventConsumer, state, emittedPlanLifecycleCount);
                if (dispatchStatus == ToolDispatchStatus.FINISHED) {
                    return state;
                }
                if (state.planExecutionCompleted) {
                    emitStreamEvent(eventConsumer, "process", "计划步骤已完成，正在生成最终答案");
                    break;
                }
                continue;
            }
            if (finalAnswerSynthesizer.handleModelFinalAnswer(input, round, state)) {
                return state;
            }
        }

        // 澄清恰好发生在最后一个正常轮次时，额外保留一次模型调用生成面向用户的澄清回复。
        if (state.inClarificationStage) {
            int clarificationRound = MAX_TOOL_LOOP_ROUNDS + 1;
            state.lastResponse = reasoning(
                    input, clarificationRound, state, eventConsumer, useModelStream);
            List<AgentToolCall> unexpectedCalls = toolCallExtractor.extract(state.lastResponse);
            if (!unexpectedCalls.isEmpty()) {
                state.riskFlags.add("clarification_final_tool_call_blocked");
            }
            state.lastFinalAnswer = json.asMap(state.lastResponse.get("final_answer"));
            if (state.lastFinalAnswer == null || state.lastFinalAnswer.isEmpty()) {
                state.lastFinalAnswer =
                        finalAnswerSynthesizer.buildClarificationFinalAnswerFallback(state.clarificationData);
            }
            state.blockedReason = "等待用户确认澄清信息";
            state.riskFlags.add("awaiting_user_clarification");
            return state;
        }

        finalAnswerSynthesizer.handleRoundsExhausted(input, state, round -> {
            Map<String, Object> response = reasoning(input, round, state);
            if (!"react".equals(state.routeDecision.getExecutionMode())) return response;
            InvestigationStateTracker.UpdateResult updateResult =
                    investigationStateTracker.apply(state, response, round);
            if (updateResult.valid()) {
                rememberInvestigationWarnings(state, updateResult);
                return response;
            }
            state.riskFlags.add("react_final_synthesis_reasoning_update_invalid");
            state.validationFeedback = String.join("；", updateResult.errors());
            Map<String, Object> rejected = new LinkedHashMap<>(response);
            rejected.put("final_answer", Map.of());
            return rejected;
        });
        return state;
    }

    private void rememberInvestigationWarnings(
            RuntimeState state, InvestigationStateTracker.UpdateResult updateResult) {
        if (updateResult.warnings().isEmpty()) return;
        state.ephemeralMemory.add("调查状态已做字段级安全纠正：" + String.join("；", updateResult.warnings()));
    }

    /**
     * 澄清是最高优先级控制动作，任何模式都不得把它与计划或业务工具混合执行；
     * direct/react 另外要求每轮只观察一个动作结果，并拒绝重复的 tool+input。
     */
    private List<AgentToolCall> constrainAdaptiveActions(
            List<AgentToolCall> toolCalls, RuntimeState state) {
        String mode = state.routeDecision.getExecutionMode();
        List<AgentToolCall> clarificationCalls = toolCalls.stream()
                .filter(call -> "clarify_requirement".equals(call.getName()))
                .toList();
        if (!clarificationCalls.isEmpty()) {
            if (toolCalls.size() > 1) {
                state.riskFlags.add("clarification_mixed_actions_blocked");
                state.validationFeedback = "澄清动作不得与 create_plan 或业务工具同时执行；"
                        + "本轮只执行 clarify_requirement。";
            }
            AgentToolCall clarification = clarificationCalls.get(0);
            String fingerprint = ToolFailureTracker.fingerprint(
                    "clarify_requirement", clarification.getInput(), json);
            if (blockActiveFailure(clarification, fingerprint, state)) return List.of();
            if (!state.observedActionFingerprints.add(fingerprint)) {
                state.riskFlags.add("repeated_clarification_action_blocked");
                state.validationFeedback = "相同澄清请求已经执行过；请使用已有澄清结果生成回复，"
                        + "不要再次调用 clarify_requirement。";
                return List.of();
            }
            return List.of(clarification);
        }
        if ("planned".equals(mode)) {
            if (state.planRecoveryRequired) {
                List<AgentToolCall> revisions = toolCalls.stream()
                        .filter(call -> "revise_plan".equals(call.getName()))
                        .toList();
                if (revisions.isEmpty()) {
                    state.riskFlags.add("plan_recovery_invalid_action_blocked");
                    state.validationFeedback = "当前计划处于失败恢复状态；工具动作只能是 revise_plan。"
                            + "如果不再修订计划，请停止调用工具并输出基于现有证据的保守 final_answer。";
                    return List.of();
                }
                if (toolCalls.size() > 1 || revisions.size() > 1) {
                    state.riskFlags.add("plan_recovery_multiple_actions_truncated");
                    state.validationFeedback = "计划恢复每轮只允许一个 revise_plan；本轮忽略其他动作。";
                }
                // revise_plan 的原始输入通常为空，实际失败上下文由 Dispatcher 自动补齐；
                // 这里不按原始输入做重复指纹，避免把新的修订上下文误判为重复动作。
                return List.of(revisions.get(0));
            }
            if (state.latestPlan.isEmpty() && toolCalls.size() > 1) {
                state.riskFlags.add("planned_initial_multiple_actions_truncated");
                state.validationFeedback = "planned 首轮只能选择一个控制动作，本轮仅执行第一项。";
            }
            if (state.latestPlan.isEmpty()) {
                AgentToolCall selected = toolCalls.get(0);
                String fingerprint = ToolFailureTracker.fingerprint(
                        selected.getName(), selected.getInput(), json);
                if (blockActiveFailure(selected, fingerprint, state)) return List.of();
                state.observedActionFingerprints.add(fingerprint);
                return List.of(selected);
            }
            AgentToolCall selected = toolCalls.get(0);
            if (toolCalls.size() > 1) {
                state.riskFlags.add("planned_multiple_actions_truncated");
                state.validationFeedback = "计划建立后的动态修正每轮只允许一个动作；本轮仅执行第一项。";
            }
            String fingerprint = ToolFailureTracker.fingerprint(selected.getName(), selected.getInput(), json);
            if (blockActiveFailure(selected, fingerprint, state)) return List.of();
            if (!state.observedActionFingerprints.add(fingerprint)) {
                state.riskFlags.add("planned_repeated_action_blocked");
                state.validationFeedback = "相同 tool+input 已在计划或后续推理中执行过；"
                        + "请选择不同工具、调用 revise_plan，或基于现有证据结束。";
                return List.of();
            }
            return List.of(selected);
        }
        if (("direct".equals(mode) || "react".equals(mode))
                && !toolCallExtractor.usesSingleActionProtocol(state.lastResponse)) {
            state.riskFlags.add(mode + "_legacy_action_protocol_used");
            if (toolCalls.size() > 1) {
                state.validationFeedback = "direct/react 必须使用 selected_action 单动作协议；"
                        + "检测到旧版多个 tool_calls，本轮兼容执行第一项并忽略其余动作。";
            }
        }
        AgentToolCall selected = toolCalls.get(0);
        if (toolCalls.size() > 1) {
            state.riskFlags.add(mode + "_multiple_actions_truncated");
            state.validationFeedback = "动态执行每轮只允许一个工具动作；本轮仅执行第一项，"
                    + "下一轮必须先观察其结果再决定后续动作。";
        }
        String fingerprint = ToolFailureTracker.fingerprint(selected.getName(), selected.getInput(), json);
        if (blockActiveFailure(selected, fingerprint, state)) return List.of();
        if (!state.observedActionFingerprints.add(fingerprint)) {
            state.riskFlags.add(mode + "_repeated_action_blocked");
            state.validationFeedback = "相同 tool+input 已经执行过，请根据已有观察选择不同动作，"
                    + "或在证据充分时输出 final_answer。";
            return List.of();
        }
        return List.of(selected);
    }

    /** 在分发前阻止已失败的同参调用，以及当前已标记不可用的整个工具。 */
    private boolean blockActiveFailure(
            AgentToolCall call, String fingerprint, RuntimeState state) {
        String toolName = json.normalize(call.getName(), "");
        if (state.unavailableTools.contains(toolName)) {
            state.riskFlags.add("unavailable_tool_call_blocked_" + toolName);
            state.validationFeedback = "工具 " + toolName + " 当前依赖不可用；"
                    + "不得更换参数重复调用，只能遵循 active_tool_failures.allowed_actions 恢复。";
            return true;
        }
        if (state.blockedActionFingerprints.contains(fingerprint)) {
            ToolFailureRecord failure = state.activeToolFailures.get(fingerprint);
            state.riskFlags.add("failed_same_call_blocked_" + toolName);
            state.validationFeedback = failure == null
                    ? "相同 tool+input 已确认失败；请修改输入、使用替代工具或保守结束。"
                    : "相同 tool+input 已确认失败（" + failure.error().code() + "）；"
                    + "只能遵循 active_tool_failures.allowed_actions 恢复。";
            return true;
        }
        return false;
    }

    /** 向流式页面发送结构化澄清事件，页面可区分第一轮澄清和执行中澄清。 */
    private void emitClarificationEvent(
            Consumer<Map<String, Object>> eventConsumer, RuntimeState state) {
        if (eventConsumer == null) {
            return;
        }
        Map<String, Object> data = state.clarificationData;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "process");
        event.put("category", "clarification");
        event.put("phase", json.normalize(String.valueOf(data.get("phase")), "initial"));
        event.put("action", json.normalize(String.valueOf(data.get("action")), "ask"));
        event.put("message", "runtime".equals(data.get("phase"))
                ? "执行过程中发现必须由用户确认的关键分支，正在生成澄清问题"
                : "开始执行前缺少关键信息，正在生成澄清问题");
        eventConsumer.accept(event);
    }

    private Map<String, Object> reasoning(String input, int round, RuntimeState state) {
        return reasoning(input, round, state, null, false);
    }

    private Map<String, Object> reasoning(
            String input,
            int round,
            RuntimeState state,
            Consumer<Map<String, Object>> eventConsumer,
            boolean useModelStream) {
        if (state.latestPlan != null && !state.latestPlan.isEmpty()) {
            state.ephemeralMemory.add("当前计划提示: " + json.toJson(state.latestPlan)
                    + "；最近校验反馈: " + json.normalize(state.validationFeedback, "无"));
        }
        WorkingContext context = contextManager.project(new ContextRequest(
                json.toTree(state.latestPlan),
                json.toTree(state.investigationState),
                json.toTree(state.executionLog),
                json.toTree(state.ephemeralMemory),
                json.toTree(state.businessDecisions),
                json.toTree(state.activeToolFailures.values().stream()
                        .map(ToolFailureRecord::toPublicMap)
                        .toList()),
                state.validationFeedback,
                json.toTree(state.riskFlags)), promptBudget());
        String prompt = promptAssembler.assembleActionPrompt(
                promptProvider,
                systemInstruction,
                input,
                state.routeDecision.getExecutionMode(),
                state.finalSynthesisStage ? List.of() : visibleToolSchemas(state),
                context,
                promptBudget());
        state.trace.record(ExecutionEventType.ACTION_MODEL_REQUESTED, Map.of(
                "round", round,
                "prompt", prompt,
                "working_context", context), Map.of());
        var rawResponse = useModelStream
                ? chatModelStream(prompt, buildModelProgressConsumer(eventConsumer, "第 " + round + " 轮模型正在流式返回"))
                : chatModel(prompt);
        Map<String, Object> response = json.asMap(rawResponse);
        state.trace.record(ExecutionEventType.ACTION_MODEL_RESPONDED, Map.of("round", round), response);
        state.executionLog.add(buildReasoningLog(
                input, round, state.validationFeedback, state.businessDecisions,
                state.activeToolFailures.values().stream()
                        .map(ToolFailureRecord::toPublicMap)
                        .toList(),
                state.investigationState,
                response));
        state.lastValidationFeedback = state.validationFeedback;
        state.validationFeedback = "";
        state.ephemeralMemory.clear();
        return response;
    }

    private PromptBudget promptBudget() {
        return new PromptBudget(
                properties.getContextMaxPromptCharacters(),
                properties.getContextMaxHistoryItems(),
                properties.getContextMaxEvidenceItems());
    }

    private Consumer<String> buildModelProgressConsumer(
            Consumer<Map<String, Object>> eventConsumer, String progressText) {
        final int[] charCount = new int[] {0};
        return delta -> {
            if (eventConsumer == null || delta == null || delta.isBlank()) {
                return;
            }
            charCount[0] += delta.length();
            if (charCount[0] == delta.length() || charCount[0] % 160 < delta.length()) {
                emitStreamEvent(eventConsumer, "process", progressText);
            }
        };
    }

    private void emitToolCalls(Consumer<Map<String, Object>> eventConsumer, List<AgentToolCall> toolCalls) {
        for (AgentToolCall call : toolCalls) {
            String toolName = json.normalize(call.getName(), "");
            if (!toolName.isBlank()) {
                emitStreamEvent(eventConsumer, "process", "准备调用工具：" + toolName);
            }
        }
    }

    /** 推送经过运行时校验的结构化调查摘要，不发送 Prompt 或模型私有推理文本。 */
    private void emitReasoningUpdate(
            Consumer<Map<String, Object>> eventConsumer,
            int round,
            Map<String, Object> response,
            RuntimeState state,
            List<String> warnings) {
        if (eventConsumer == null) return;
        Map<String, Object> update = json.asMap(response.get("reasoning_update"));
        if (update.isEmpty()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "reasoning_update");
        payload.put("category", "reasoning");
        payload.put("round", round);
        payload.put("message", "第 " + round + " 轮调查状态已更新");
        payload.put("reasoningUpdate", update);
        payload.put("investigationState", new LinkedHashMap<>(state.investigationState));
        payload.put("selectedAction", json.asMap(response.get("selected_action")));
        payload.put("warnings", warnings == null ? List.of() : List.copyOf(warnings));
        eventConsumer.accept(payload);
    }

    private void emitReasoningValidation(
            Consumer<Map<String, Object>> eventConsumer, int round, List<String> errors) {
        if (eventConsumer == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "reasoning_update");
        payload.put("category", "reasoning_validation");
        payload.put("round", round);
        payload.put("status", "rejected");
        payload.put("message", "第 " + round + " 轮结构化调查更新未通过校验，等待下一轮修正");
        payload.put("errors", errors == null ? List.of() : List.copyOf(errors));
        eventConsumer.accept(payload);
    }

    /** 将本轮新增的安全工具结果投影到流式页面，原始 data 和内部异常不进入事件。 */
    private void emitToolObservations(
            Consumer<Map<String, Object>> eventConsumer,
            int round,
            RuntimeState state,
            int executionLogStart) {
        if (eventConsumer == null) return;
        int start = Math.max(0, Math.min(executionLogStart, state.executionLog.size()));
        for (int i = start; i < state.executionLog.size(); i++) {
            AgentExecutionLogEntry log = state.executionLog.get(i);
            Map<String, Object> output = json.asMap(log.getOutput());
            String status = json.normalize(String.valueOf(output.get("status")), "");
            if (!"success".equals(status) && !"failed".equals(status)) continue;
            String toolName = json.normalize(log.getToolName(), "");
            if (toolName.isBlank() || "reasoning".equals(toolName)
                    || "independent_verifier".equals(toolName)) continue;
            Map<String, Object> error = json.asMap(output.get("error"));
            Map<String, Object> metadata = json.asMap(output.get("metadata"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "tool_observation");
            payload.put("category", "tool");
            payload.put("round", round);
            payload.put("step", log.getStep());
            payload.put("toolName", toolName);
            payload.put("status", status);
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("attemptCount", intValue(metadata.get("attempt_count"), 1));
            attempt.put("maxAttempts", intValue(metadata.get("max_attempts"), 1));
            attempt.put("retryExhausted", Boolean.TRUE.equals(metadata.get("retry_exhausted")));
            attempt.put("retrySkipped", Boolean.TRUE.equals(metadata.get("retry_skipped")));
            attempt.put("stopReason", metadata.getOrDefault("retry_stop_reason", ""));
            attempt.put("totalBackoffMs", longValue(metadata.get("total_backoff_ms"), 0L));
            attempt.put("totalDurationMs", longValue(metadata.get("total_duration_ms"), 0L));
            attempt.put("retryBudgetRemaining", intValue(metadata.get("retry_budget_remaining"), -1));
            attempt.put("requestRemainingMs", longValue(metadata.get("request_remaining_ms"), -1L));
            attempt.put("attempts", metadata.getOrDefault("attempts", List.of()));
            payload.put("attempt", attempt);
            payload.put("error", error);
            payload.put("validationErrors", json.asStringList(output.get("validation_errors")));
            payload.put("message", toolObservationMessage(toolName, status, error, output));
            eventConsumer.accept(payload);
        }
    }

    private String toolObservationMessage(
            String toolName, String status, Map<String, Object> error, Map<String, Object> output) {
        if ("success".equals(status)) return "工具 " + toolName + " 执行成功";
        Object messageValue = error.get("public_message");
        String publicMessage = json.normalize(messageValue == null ? "" : String.valueOf(messageValue), "");
        if (publicMessage.isBlank()) {
            List<String> errors = json.asStringList(output.get("validation_errors"));
            publicMessage = errors.isEmpty() ? "执行失败" : errors.get(0);
        }
        return "工具 " + toolName + " 执行失败：" + publicMessage;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private int emitPlanLifecycle(
            Consumer<Map<String, Object>> eventConsumer, RuntimeState state, int emittedCount) {
        if (eventConsumer == null || state.planLifecycle.isEmpty()) {
            return emittedCount;
        }
        int start = Math.max(0, Math.min(emittedCount, state.planLifecycle.size()));
        for (int i = start; i < state.planLifecycle.size(); i++) {
            Map<String, Object> item = state.planLifecycle.get(i);
            Object event = item.get("event");
            Object stepName = item.get("step_name");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "process");
            payload.put("category", "plan");
            payload.put("planEvent", String.valueOf(event));
            payload.putAll(item);
            payload.put("message", stepName == null
                    ? "计划事件：" + event
                    : "计划步骤：" + stepName + "（" + event + "）");
            eventConsumer.accept(payload);
        }
        return state.planLifecycle.size();
    }

    private void emitStreamEvent(Consumer<Map<String, Object>> eventConsumer, String type, String message) {
        if (eventConsumer != null && message != null && !message.isBlank()) {
            eventConsumer.accept(Map.of("type", type, "message", message));
        }
    }
}
