package com.agent.javascope.agent.runtime;

import com.agent.javascope.agent.prompt.PromptAssembler;
import com.agent.javascope.agent.routing.AgentToolCallExtractor;
import com.agent.javascope.agent.routing.InputRouter;
import com.agent.javascope.agent.strategy.*;
import com.agent.javascope.context.projection.ContextManager;
import com.agent.javascope.context.trace.ExecutionEventType;
import com.agent.javascope.context.trace.ExecutionLogStore;
import com.agent.javascope.context.trace.ExecutionTrace;
import com.agent.javascope.entity.plan.PlanStepView;
import com.agent.javascope.entity.routing.RouteDecision;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.tools.validation.StepValidatorTool;
import com.agent.javascope.verifier.IndependentVerifierService;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** 统一运行时门面：创建执行轨迹、完成路由并选择执行策略。 */
public class ReActAgent extends Agent {

    private final InputRouter inputRouter;
    private final ExecutionStrategySelector strategySelector;
    private final ExecutionLogStore executionLogStore;

    public ReActAgent(
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
        this.executionLogStore = executionLogStore;
        this.inputRouter = new InputRouter(promptProvider, modelClient, json, new AgentToolCallExtractor(json));
        ExecutionStrategy directReply = new DirectReplyExecutionStrategy(
                properties, promptProvider, toolExecutor, modelClient, json);
        ExecutionStrategy planReAct = new PlanReActExecutionStrategy(
                properties, promptProvider, toolExecutor, modelClient, json, stepValidatorTool,
                independentVerifierService, executionLogStore, contextManager, promptAssembler);
        this.strategySelector = new ExecutionStrategySelector(List.of(directReply, planReAct));
    }

    public String call(String input, String sessionId, String userId) {
        RuntimeState state = execute(input, sessionId, userId, null, false);
        completeTrace(state);
        return respondAsText(state.trace.executionId(), state.latestPlanResult, buildPlanView(state), state.executionLog,
                state.revisedPlan, state.planLifecycle, state.blockedReason, state.lastFinalAnswer, state.riskFlags);
    }

    public String callStream(String input, String sessionId, String userId, Consumer<String> chunkConsumer) {
        RuntimeState state = execute(input, sessionId, userId, null, false);
        completeTrace(state);
        return respondAsStream(state.trace.executionId(), state.latestPlanResult, buildPlanView(state), state.executionLog,
                state.revisedPlan, state.planLifecycle, state.blockedReason, state.lastFinalAnswer, state.riskFlags, chunkConsumer);
    }

    public String callWithModelStream(
            String input, String sessionId, String userId, Consumer<Map<String, Object>> eventConsumer) {
        RuntimeState state = execute(input, sessionId, userId, eventConsumer, true);
        completeTrace(state);
        emitProgress(eventConsumer, "Agent 执行完成，正在整理最终回答");
        return respondAsText(state.trace.executionId(), state.latestPlanResult, buildPlanView(state), state.executionLog,
                state.revisedPlan, state.planLifecycle, state.blockedReason, state.lastFinalAnswer, state.riskFlags);
    }

    public Flux<Map<String, Object>> callWithModelFlux(String input, String sessionId, String userId) {
        return Flux.<Map<String, Object>>create(sink -> {
            try {
                String reply = callWithModelStream(input, sessionId, userId, event -> { if (!sink.isCancelled()) sink.next(event); });
                if (!sink.isCancelled()) {
                    sink.next(Map.of("type", "raw_reply", "rawReply", reply));
                    sink.complete();
                }
            } catch (Exception e) {
                if (!sink.isCancelled()) sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private RuntimeState execute(
            String input, String sessionId, String userId, Consumer<Map<String, Object>> eventConsumer, boolean useModelStream) {
        ensureAgentInitialized();
        RuntimeState state = new RuntimeState(new ExecutionTrace(UUID.randomUUID().toString(), executionLogStore, json));
        state.trace.record(ExecutionEventType.USER_INPUT_RECEIVED, Map.of(
                "session_id", sessionId == null ? "" : sessionId,
                "user_id", userId == null ? "" : userId,
                "input", input == null ? "" : input), Map.of());
        emitProgress(eventConsumer, "正在识别本轮输入类型");
        RouteDecision decision = useModelStream
                ? inputRouter.routeStream(input, state, systemInstruction, delta -> emitProgress(eventConsumer, "路由模型正在流式返回"))
                : inputRouter.route(input, state, systemInstruction);
        state.routeDecision = decision;
        emitProgress(eventConsumer, "输入类型：" + decision.getRoute() + "/" + decision.getExecutionMode());
        return strategySelector.select(decision).execute(new ExecutionRequest(input, state, eventConsumer, useModelStream));
    }

    private void completeTrace(RuntimeState state) {
        state.trace.record(ExecutionEventType.FINAL_ANSWER_GENERATED, Map.of(), state.lastFinalAnswer);
        state.trace.record(ExecutionEventType.EXECUTION_COMPLETED, Map.of(
                "blocked_reason", state.blockedReason,
                "risk_flags", state.riskFlags), Map.of(
                "execution_log_size", state.executionLog.size(),
                "trace_event_size", state.trace.events().size()));
    }

    private List<PlanStepView> buildPlanView(RuntimeState state) {
        return state.planSteps.stream().map(PlanStepView::new).toList();
    }

    private void emitProgress(Consumer<Map<String, Object>> consumer, String message) {
        if (consumer != null && message != null && !message.isBlank()) consumer.accept(Map.of("type", "process", "message", message));
    }
}
