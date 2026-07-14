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
        String executionMode = state.routeDecision.getExecutionMode();
        int maxRounds = properties.resolveMaxRounds(executionMode);
        int reactMaxToolCalls = Math.max(1, properties.getReactMaxToolCalls());

        for (int round = 1; round <= maxRounds; round++) {
            if ("react".equals(executionMode)
                    && !state.inClarificationStage
                    && state.observedActionFingerprints.size() >= reactMaxToolCalls) {
                state.riskFlags.add("react_tool_call_budget_exhausted");
                state.validationFeedback = "ReAct 工具调用预算已耗尽。请基于已有观察生成保守的 final_answer，"
                        + "不要继续调用工具。";
                emitStreamEvent(eventConsumer, "process", "ReAct 已达到工具调用上限，正在基于已有观察生成回答");
                break;
            }
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
            int clarificationRound = maxRounds + 1;
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
                input, round, state.validationFeedback, state.businessDecisions, response));
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
