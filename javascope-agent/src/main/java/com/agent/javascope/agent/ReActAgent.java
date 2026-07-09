package com.agent.javascope.agent;

import com.agent.javascope.chat.AgentChatModelClient;
import com.agent.javascope.config.AgentRuntimeProperties;
import com.agent.javascope.entity.AgentExecutionLogEntry;
import com.agent.javascope.entity.AgentToolCall;
import com.agent.javascope.entity.PlanStepDefinition;
import com.agent.javascope.entity.PlanStepState;
import com.agent.javascope.entity.PlanStepView;
import com.agent.javascope.entity.RouteDecision;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.tools.AgentToolExecutor;
import com.agent.javascope.tools.StepValidatorTool;
import com.agent.javascope.verifier.IndependentVerifierService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.agent.javascope.util.AgentJsonCodecUtil;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Component
public class ReActAgent extends Agent {

    private final ToolCallDispatcher toolCallDispatcher;
    private final InputRouter inputRouter;
    private final FinalAnswerSynthesizer finalAnswerSynthesizer;
    private final AgentToolCallExtractor toolCallExtractor;

    public ReActAgent(
            AgentRuntimeProperties properties,
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json,
            StepValidatorTool stepValidatorTool,
            IndependentVerifierService independentVerifierService) {
        super(properties, promptProvider, toolExecutor, modelClient, json);
        this.toolCallExtractor = new AgentToolCallExtractor(json);
        this.inputRouter = new InputRouter(promptProvider, modelClient, json, toolCallExtractor);
        this.finalAnswerSynthesizer =
                new FinalAnswerSynthesizer(properties, json, independentVerifierService, toolCallExtractor);
        PlanExecutor planExecutor = new PlanExecutor(properties, toolExecutor, json, stepValidatorTool);
        this.toolCallDispatcher = new ToolCallDispatcher(promptProvider, toolExecutor, json, planExecutor);
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

    public String callWithModelStream(
            String input, String sessionId, String userId, Consumer<Map<String, Object>> eventConsumer) {
        RuntimeState state = execute(input, eventConsumer, true);
        emitStreamEvent(eventConsumer, "process", "Agent 执行完成，正在整理最终回答");
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

    public Flux<Map<String, Object>> callWithModelFlux(String input, String sessionId, String userId) {
        return Flux.<Map<String, Object>>create(sink -> {
            try {
                String rawReply = callWithModelStream(input, sessionId, userId, event -> {
                    if (!sink.isCancelled()) {
                        sink.next(event);
                    }
                });
                if (!sink.isCancelled()) {
                    sink.next(Map.of("type", "raw_reply", "rawReply", rawReply));
                    sink.complete();
                }
            } catch (Exception e) {
                if (!sink.isCancelled()) {
                    sink.error(e);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private RuntimeState execute(String input) {
        return execute(input, null, false);
    }

    private RuntimeState execute(String input, Consumer<Map<String, Object>> eventConsumer, boolean useModelStream) {
        ensureAgentInitialized();
        RuntimeState state = new RuntimeState();
        emitStreamEvent(eventConsumer, "process", "正在识别本轮输入类型");
        RouteDecision routeDecision = useModelStream
                ? inputRouter.routeStream(
                        input,
                        state,
                        systemInstruction,
                        buildModelProgressConsumer(eventConsumer, "路由模型正在流式返回"))
                : inputRouter.route(input, state, systemInstruction);
        state.routeDecision = routeDecision;
        emitStreamEvent(eventConsumer, "process", "输入类型：" + routeDecision.getRoute());
        if (!"task".equals(routeDecision.getRoute())) {
            emitStreamEvent(eventConsumer, "process", "进入直接回复模式");
            state.lastFinalAnswer = useModelStream
                    ? inputRouter.buildDirectRouteFinalAnswerStream(
                            input,
                            routeDecision,
                            state,
                            systemInstruction,
                            buildModelProgressConsumer(eventConsumer, "回复模型正在流式返回"))
                    : inputRouter.buildDirectRouteFinalAnswer(input, routeDecision, state, systemInstruction);
            finalAnswerSynthesizer.applyBlockedReason(state);
            return state;
        }

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
        String rawResponse = useModelStream
                ? chatModelStream(prompt, buildModelProgressConsumer(eventConsumer, "第 " + round + " 轮模型正在流式返回"))
                : chatModel(prompt);
        Map<String, Object> response = json.parseJson(rawResponse);
        state.executionLog.add(buildReasoningLog(input, round, state.validationFeedback, response));
        state.lastValidationFeedback = state.validationFeedback;
        state.validationFeedback = "";
        state.ephemeralMemory.clear();
        return response;
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
