package com.agent.javascope.agent.strategy;

import com.agent.javascope.agent.finalization.FinalAnswerSynthesizer;
import com.agent.javascope.agent.planning.PlanExecutor;
import com.agent.javascope.agent.planning.ToolCallDispatcher;
import com.agent.javascope.agent.planning.ToolDispatchStatus;
import com.agent.javascope.agent.prompt.PromptAssembler;
import com.agent.javascope.agent.routing.AgentToolCallExtractor;
import com.agent.javascope.agent.runtime.Agent;
import com.agent.javascope.agent.runtime.RuntimeState;
import com.agent.javascope.context.budget.PromptBudget;
import com.agent.javascope.context.projection.ContextManager;
import com.agent.javascope.context.projection.ContextRequest;
import com.agent.javascope.context.projection.WorkingContext;
import com.agent.javascope.context.trace.ExecutionEventType;
import com.agent.javascope.context.trace.ExecutionLogStore;
import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.entity.execution.AgentToolCall;
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
                ToolDispatchStatus dispatchStatus = toolCallDispatcher.execute(input, round, toolCalls, state);
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

        finalAnswerSynthesizer.handleRoundsExhausted(input, state, round -> reasoning(input, round, state));
        return state;
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
            String fingerprint = "clarify_requirement|" + json.toJson(clarification.getInput());
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
                return List.of(toolCalls.get(0));
            }
            if (state.latestPlan.isEmpty()) {
                return toolCalls;
            }
            AgentToolCall selected = toolCalls.get(0);
            if (toolCalls.size() > 1) {
                state.riskFlags.add("planned_multiple_actions_truncated");
                state.validationFeedback = "计划建立后的动态修正每轮只允许一个动作；本轮仅执行第一项。";
            }
            String fingerprint = json.normalize(selected.getName(), "") + "|" + json.toJson(selected.getInput());
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
        if ("react".equals(mode)) {
            List<String> decisionErrors = validateDecisionSummary(state.lastResponse, state);
            if (!decisionErrors.isEmpty()) {
                state.riskFlags.add("react_decision_summary_invalid");
                state.validationFeedback = "ReAct decision_summary 不符合调查契约："
                        + String.join("; ", decisionErrors);
                return List.of();
            }
        }
        AgentToolCall selected = toolCalls.get(0);
        if (toolCalls.size() > 1) {
            state.riskFlags.add(mode + "_multiple_actions_truncated");
            state.validationFeedback = "动态执行每轮只允许一个工具动作；本轮仅执行第一项，"
                    + "下一轮必须先观察其结果再决定后续动作。";
        }
        String fingerprint = json.normalize(selected.getName(), "") + "|" + json.toJson(selected.getInput());
        if (!state.observedActionFingerprints.add(fingerprint)) {
            state.riskFlags.add(mode + "_repeated_action_blocked");
            state.validationFeedback = "相同 tool+input 已经执行过，请根据已有观察选择不同动作，"
                    + "或在证据充分时输出 final_answer。";
            return List.of();
        }
        return List.of(selected);
    }

    private List<String> validateDecisionSummary(Map<String, Object> response, RuntimeState state) {
        List<String> errors = new java.util.ArrayList<>();
        Map<String, Object> summary = json.asMap(response == null ? null : response.get("decision_summary"));
        for (String field : List.of(
                "current_question", "action_reason", "expected_information_gain")) {
            if (!hasText(summary, field)) errors.add(field + " 不能为空");
        }
        Map<String, Object> informationNeed = json.asMap(summary.get("next_information_needed"));
        if (informationNeed.size() != 1 || !hasText(informationNeed, "gap")) {
            errors.add("next_information_needed 必须是只包含一个非空 gap 的对象");
        }
        Object updatesValue = summary.get("hypothesis_updates");
        if (!(updatesValue instanceof List<?> updates)) {
            errors.add("hypothesis_updates 必须是数组");
            return errors;
        }
        Map<String, Map<String, Object>> existingHypotheses = indexedHypotheses(state.investigationState);
        for (int i = 0; i < updates.size(); i++) {
            Map<String, Object> update = json.asMap(updates.get(i));
            String prefix = "hypothesis_updates[" + i + "]";
            if (!hasText(update, "hypothesis")) errors.add(prefix + ".hypothesis 不能为空");
            if (!hasText(update, "update_reason")) errors.add(prefix + ".update_reason 不能为空");
            String status = update.get("status") instanceof String text ? text : "";
            if (!List.of("supported", "weakened", "unverified").contains(status)) {
                errors.add(prefix + ".status 非法");
            }
            String strength = update.get("evidence_strength") instanceof String text ? text : "";
            if (!List.of("none", "weak", "medium", "strong").contains(strength)) {
                errors.add(prefix + ".evidence_strength 非法");
            }
            Map<String, Object> previous = existingHypotheses.get(String.valueOf(update.get("hypothesis")));
            if (previous != null && sameHypothesisState(previous, update)) {
                errors.add(prefix + " 未产生增量变化，不应重复输出");
            }
            boolean directlyRelevant = json.asBoolean(update.get("directly_relevant"), false);
            List<String> refs = json.asStringList(update.get("evidence_refs"));
            if (!"unverified".equals(status)) {
                if (!directlyRelevant) errors.add(prefix + " 只有 directly_relevant=true 才能改变假设状态");
                if (refs.isEmpty()) errors.add(prefix + " 改变状态时必须提供 evidence_refs");
                if ("none".equals(strength)) errors.add(prefix + " 改变状态时 evidence_strength 不能为 none");
                for (String ref : refs) {
                    if (!isKnownEvidenceReference(ref, state.executionLog)) {
                        errors.add(prefix + " 引用了不存在的证据: " + ref);
                    }
                }
            }
        }
        return errors;
    }

    private Map<String, Map<String, Object>> indexedHypotheses(Map<String, Object> investigationState) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        Object hypothesesValue = investigationState.get("hypotheses");
        if (!(hypothesesValue instanceof List<?>)) {
            hypothesesValue = investigationState.get("hypothesis_updates");
        }
        if (hypothesesValue instanceof List<?> hypotheses) {
            for (Object value : hypotheses) {
                Map<String, Object> hypothesis = json.asMap(value);
                if (hasText(hypothesis, "hypothesis")) {
                    indexed.put(String.valueOf(hypothesis.get("hypothesis")), hypothesis);
                }
            }
        }
        return indexed;
    }

    private boolean sameHypothesisState(Map<String, Object> previous, Map<String, Object> update) {
        for (String field : List.of(
                "status", "update_reason", "evidence_strength", "directly_relevant", "evidence_refs")) {
            if (!java.util.Objects.equals(previous.get(field), update.get(field))) return false;
        }
        return true;
    }

    private void mergeInvestigationState(Map<String, Object> summary, RuntimeState state) {
        Map<String, Object> merged = new LinkedHashMap<>(state.investigationState);
        merged.remove("latest_observation");
        merged.remove("hypothesis_updates");
        for (String field : List.of(
                "current_question", "next_information_needed", "action_reason",
                "expected_information_gain", "stop_reason")) {
            if (summary.containsKey(field)) merged.put(field, summary.get(field));
        }

        Map<String, Map<String, Object>> hypotheses = indexedHypotheses(state.investigationState);
        Object updatesValue = summary.get("hypothesis_updates");
        if (updatesValue instanceof List<?> updates) {
            for (Object value : updates) {
                Map<String, Object> update = json.asMap(value);
                if (hasText(update, "hypothesis")) {
                    hypotheses.put(String.valueOf(update.get("hypothesis")), new LinkedHashMap<>(update));
                }
            }
        }
        merged.put("hypotheses", new java.util.ArrayList<>(hypotheses.values()));
        state.investigationState.clear();
        state.investigationState.putAll(merged);
    }

    private boolean isKnownEvidenceReference(String reference, List<AgentExecutionLogEntry> executionLog) {
        if (reference == null || reference.isBlank()) return false;
        for (AgentExecutionLogEntry entry : executionLog) {
            String toolName = entry.getToolName();
            String step = entry.getStep();
            if (reference.equals(toolName)
                    || reference.startsWith(toolName + ".")
                    || reference.equals(step)
                    || reference.startsWith(step + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(Map<String, Object> source, String field) {
        return source.get(field) instanceof String text && !text.isBlank();
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
                json.toTree(state.executionLog),
                json.toTree(state.ephemeralMemory),
                json.toTree(state.businessDecisions),
                json.toTree(state.investigationState),
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
        Map<String, Object> priorInvestigationState = new LinkedHashMap<>(state.investigationState);
        var rawResponse = useModelStream
                ? chatModelStream(prompt, buildModelProgressConsumer(eventConsumer, "第 " + round + " 轮模型正在流式返回"))
                : chatModel(prompt);
        Map<String, Object> response = json.asMap(rawResponse);
        if ("react".equals(state.routeDecision.getExecutionMode())) {
            Map<String, Object> decisionSummary = json.asMap(response.get("decision_summary"));
            if (!decisionSummary.isEmpty() && validateDecisionSummary(response, state).isEmpty()) {
                mergeInvestigationState(decisionSummary, state);
            }
        }
        state.trace.record(ExecutionEventType.ACTION_MODEL_RESPONDED, Map.of("round", round), response);
        state.executionLog.add(buildReasoningLog(
                input, round, state.validationFeedback, state.businessDecisions,
                priorInvestigationState, response));
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
