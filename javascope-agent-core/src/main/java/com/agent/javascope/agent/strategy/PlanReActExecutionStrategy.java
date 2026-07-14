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
import com.agent.javascope.tools.validation.StepValidatorTool;
import com.agent.javascope.verifier.IndependentVerifierService;

import java.util.List;
import java.util.Map;

/**
 * 固定可执行计划策略：计划创建时必须确定每一步的 tool 和 input，执行失败后允许 revise_plan。
 */
public class PlanReActExecutionStrategy extends AbstractToolLoopExecutionStrategy {

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
        super(properties, promptProvider, toolExecutor, modelClient, json, true, stepValidatorTool,
                independentVerifierService, executionLogStore, contextManager, promptAssembler);
    }

    @Override
    public boolean supports(RouteDecision routeDecision) {
        return routeDecision != null
                && "task".equals(routeDecision.getRoute())
                && "planned".equals(routeDecision.getExecutionMode());
    }

    /**
     * planned 首轮由同一次模型思考在“澄清”与“创建计划”之间二选一；
     * 计划建立后才暴露业务工具和 revise_plan。
     */
    @Override
    protected List<Map<String, Object>> visibleToolSchemas(RuntimeState state) {
        if (!state.latestPlan.isEmpty()) {
            return toolSchemas;
        }
        return toolSchemas.stream()
                .filter(schema -> {
                    String name = String.valueOf(schema.get("name"));
                    return "create_plan".equals(name) || "clarify_requirement".equals(name);
                })
                .toList();
    }
}
