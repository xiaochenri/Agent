package com.agent.javascope.agent.strategy;

import com.agent.javascope.agent.prompt.PromptAssembler;
import com.agent.javascope.agent.runtime.RuntimeState;
import com.agent.javascope.context.projection.ContextManager;
import com.agent.javascope.context.trace.ExecutionLogStore;
import com.agent.javascope.entity.routing.RouteDecision;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.verifier.IndependentVerifierService;

import java.util.List;
import java.util.Map;

/**
 * 动态 ReAct 策略：不创建固定计划，每轮根据已有工具观察选择下一动作。
 * direct 复用同一内核，但由 Prompt 将其限制为一次明确的事实工具调用。
 */
public class ReActExecutionStrategy extends AbstractToolLoopExecutionStrategy {

    public ReActExecutionStrategy(
            AgentRuntimeProperties properties,
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json,
            IndependentVerifierService independentVerifierService,
            ExecutionLogStore executionLogStore,
            ContextManager contextManager,
            PromptAssembler promptAssembler) {
        super(properties, promptProvider, toolExecutor, modelClient, json, false, null,
                independentVerifierService, executionLogStore, contextManager, promptAssembler);
    }

    @Override
    public boolean supports(RouteDecision routeDecision) {
        if (routeDecision == null || !"task".equals(routeDecision.getRoute())) {
            return false;
        }
        String mode = routeDecision.getExecutionMode();
        return "direct".equals(mode) || "react".equals(mode);
    }

    /** ReAct/direct 都禁止计划工具；模型只能基于观察继续选择业务工具或结束。 */
    @Override
    protected List<Map<String, Object>> visibleToolSchemas(RuntimeState state) {
        return toolSchemas.stream()
                .filter(schema -> !"create_plan".equals(String.valueOf(schema.get("name")))
                        && !"revise_plan".equals(String.valueOf(schema.get("name"))))
                .toList();
    }
}
