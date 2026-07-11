package com.agent.javascope.agent.strategy;

import com.agent.javascope.agent.routing.AgentToolCallExtractor;
import com.agent.javascope.agent.routing.InputRouter;
import com.agent.javascope.agent.runtime.Agent;
import com.agent.javascope.agent.runtime.RuntimeState;
import com.agent.javascope.entity.routing.RouteDecision;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.tool.runtime.AgentToolExecutor;

import java.util.Map;
import java.util.function.Consumer;

/** 处理 chat/meta 等无需工具调用的直接回复策略。 */
public class DirectReplyExecutionStrategy extends Agent implements ExecutionStrategy {

    private final InputRouter inputRouter;

    public DirectReplyExecutionStrategy(
            AgentRuntimeProperties properties,
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json) {
        super(properties, promptProvider, toolExecutor, modelClient, json);
        this.inputRouter = new InputRouter(promptProvider, modelClient, json, new AgentToolCallExtractor(json));
    }

    @Override
    public boolean supports(RouteDecision routeDecision) {
        return routeDecision != null && !"task".equals(routeDecision.getRoute());
    }

    @Override
    public RuntimeState execute(ExecutionRequest request) {
        ensureAgentInitialized();
        RuntimeState state = request.state();
        Consumer<Map<String, Object>> eventConsumer = request.eventConsumer();
        if (eventConsumer != null) {
            eventConsumer.accept(Map.of("type", "process", "message", "进入直接回复模式"));
        }
        state.lastFinalAnswer = request.useModelStream()
                ? inputRouter.buildDirectRouteFinalAnswerStream(
                        request.input(), state.routeDecision, state, systemInstruction,
                        delta -> { if (eventConsumer != null) eventConsumer.accept(Map.of("type", "process", "message", "回复模型正在流式返回")); })
                : inputRouter.buildDirectRouteFinalAnswer(request.input(), state.routeDecision, state, systemInstruction);
        state.blockedReason = state.lastFinalAnswer == null || state.lastFinalAnswer.isEmpty() ? "最终答案为空" : "";
        return state;
    }
}
