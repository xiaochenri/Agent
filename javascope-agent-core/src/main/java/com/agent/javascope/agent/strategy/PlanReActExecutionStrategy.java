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
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.entity.execution.AgentToolCall;
import com.agent.javascope.entity.plan.PlanStepState;
import com.agent.javascope.entity.plan.PlanStepView;
import com.agent.javascope.entity.routing.RouteDecision;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.tools.validation.StepValidatorTool;
import com.agent.javascope.verifier.IndependentVerifierService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 默认的计划型 ReAct 执行策略，保留原有路由、规划、执行和收口行为。 */
public class PlanReActExecutionStrategy extends Agent implements ExecutionStrategy {

    private final ToolCallDispatcher toolCallDispatcher;
    private final FinalAnswerSynthesizer finalAnswerSynthesizer;
    private final AgentToolCallExtractor toolCallExtractor;
    private final ContextManager contextManager;
    private final PromptAssembler promptAssembler;

    public PlanReActExecutionStrategy(
            AgentRuntimeProperties properties,
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json,
            StepValidatorTool stepValidatorTool,
            IndependentVerifierService independentVerifierService,
            ExecutionLogStore executionLogStore,
            ContextManager contextManager,
            PromptAssembler promptAssembler) {
        super(properties, promptProvider, toolExecutor, modelClient, json);
        this.toolCallExtractor = new AgentToolCallExtractor(json);
        this.finalAnswerSynthesizer =
                new FinalAnswerSynthesizer(properties, json, independentVerifierService, toolCallExtractor);
        PlanExecutor planExecutor = new PlanExecutor(properties, toolExecutor, json, stepValidatorTool);
        this.toolCallDispatcher = new ToolCallDispatcher(promptProvider, toolExecutor, json, planExecutor);
        this.contextManager = contextManager;
        this.promptAssembler = promptAssembler;
    }

    @Override
    public boolean supports(RouteDecision routeDecision) {
        return routeDecision != null && "task".equals(routeDecision.getRoute());
    }

    @Override
    public RuntimeState execute(ExecutionRequest request) {
        String input = request.input();
        RuntimeState state = request.state();
        Consumer<Map<String, Object>> eventConsumer = request.eventConsumer();
        boolean useModelStream = request.useModelStream();
        ensureAgentInitialized();

        for (int round = 1; round <= properties.getMaxRounds(); round++) {
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
                emitToolCalls(eventConsumer, toolCalls);
                ToolDispatchStatus dispatchStatus = toolCallDispatcher.execute(input, round, toolCalls, state);
                emitPlanLifecycle(eventConsumer, state);
                if (dispatchStatus == ToolDispatchStatus.FINISHED) {
                    return state;
                }
                continue;
            }

            if (finalAnswerSynthesizer.handleModelFinalAnswer(input, round, state)) {
                return state;
            }
        }

        finalAnswerSynthesizer.handleRoundsExhausted(input, state, round -> reasoning(input, round, state));
        return state;
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
        String hint = buildPlanHint(state.latestPlan, state.validationFeedback);
        if (!hint.isEmpty()) {
            state.ephemeralMemory.add(hint);
        }
        WorkingContext context = contextManager.project(new ContextRequest(
                json.toTree(state.latestPlan),
                json.toTree(state.executionLog),
                json.toTree(state.ephemeralMemory),
                state.validationFeedback,
                json.toTree(state.riskFlags)), promptBudget());
        ensureAgentInitialized();
        String prompt = promptAssembler.assembleActionPrompt(
                promptProvider,
                systemInstruction,
                input,
                state.routeDecision.getExecutionMode(),
                visibleToolSchemas(state),
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
        state.executionLog.add(buildReasoningLog(input, round, state.validationFeedback, response));
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

    /** planned 模式的首轮仅暴露 create_plan，防止模型先尝试业务工具再被运行时拒绝。 */
    private List<Map<String, Object>> visibleToolSchemas(RuntimeState state) {
        if ("direct".equals(state.routeDecision.getExecutionMode())) {
            return toolSchemas.stream()
                    .filter(schema -> !"create_plan".equals(String.valueOf(schema.get("name")))
                            && !"revise_plan".equals(String.valueOf(schema.get("name"))))
                    .toList();
        }
        if (!"planned".equals(state.routeDecision.getExecutionMode()) || !state.latestPlan.isEmpty()) {
            return toolSchemas;
        }
        return toolSchemas.stream()
                .filter(schema -> "create_plan".equals(String.valueOf(schema.get("name"))))
                .toList();
    }

    private void completeTrace(RuntimeState state) {
        state.trace.record(ExecutionEventType.FINAL_ANSWER_GENERATED, Map.of(), state.lastFinalAnswer);
        state.trace.record(ExecutionEventType.EXECUTION_COMPLETED, Map.of(
                "blocked_reason", state.blockedReason,
                "risk_flags", state.riskFlags), Map.of(
                "execution_log_size", state.executionLog.size(),
                "trace_event_size", state.trace.events().size()));
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

    private void emitPlanLifecycle(Consumer<Map<String, Object>> eventConsumer, RuntimeState state) {
        if (eventConsumer == null || state.planLifecycle.isEmpty()) {
            return;
        }
        int start = Math.max(0, state.planLifecycle.size() - 4);
        for (int i = start; i < state.planLifecycle.size(); i++) {
            Map<String, Object> item = state.planLifecycle.get(i);
            Object event = item.get("event");
            Object stepName = item.get("step_name");
            if (stepName == null) {
                emitStreamEvent(eventConsumer, "process", "计划事件：" + String.valueOf(event));
            } else {
                emitStreamEvent(eventConsumer, "process", "计划步骤：" + stepName + "（" + event + "）");
            }
        }
    }

    private void emitStreamEvent(Consumer<Map<String, Object>> eventConsumer, String type, String message) {
        if (eventConsumer == null || message == null || message.isBlank()) {
            return;
        }
        eventConsumer.accept(Map.of("type", type, "message", message));
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

    private List<PlanStepView> buildPlanView(RuntimeState state) {
        List<PlanStepView> planView = new ArrayList<>();
        for (PlanStepState step : state.planSteps) {
            planView.add(new PlanStepView(step));
        }
        return planView;
    }

}
