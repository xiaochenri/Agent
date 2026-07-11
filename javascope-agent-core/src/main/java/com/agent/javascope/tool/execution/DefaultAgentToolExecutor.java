package com.agent.javascope.tool.execution;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.tool.authorization.ToolAuthorizationDecision;
import com.agent.javascope.tool.authorization.ToolAuthorizationPolicy;
import com.agent.javascope.tool.invocation.ToolInvoker;
import com.agent.javascope.tool.middleware.ToolExecutionContext;
import com.agent.javascope.tool.middleware.ToolInvocationChain;
import com.agent.javascope.tool.middleware.ToolMiddleware;
import com.agent.javascope.tool.registry.ToolRegistry;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolExecutionStatus;
import com.agent.javascope.tool.runtime.ToolInvocation;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 统一串联工具注册、授权、治理链和实际执行的门面。 */
public class DefaultAgentToolExecutor implements AgentToolExecutor {

    private final ToolRegistry registry;
    private final ToolAuthorizationPolicy authorizationPolicy;
    private final List<ToolMiddleware> middlewares;
    private final ToolInvoker invoker;
    private final AgentJsonCodecUtil json;

    public DefaultAgentToolExecutor(
            ToolRegistry registry,
            ToolAuthorizationPolicy authorizationPolicy,
            List<ToolMiddleware> middlewares,
            ToolInvoker invoker,
            AgentJsonCodecUtil json) {
        this.registry = registry;
        this.authorizationPolicy = authorizationPolicy;
        this.middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
        this.invoker = invoker;
        this.json = json;
    }

    @Override
    public ToolExecutionResult execute(ToolInvocation invocation) {
        String toolName = invocation == null ? "" : invocation.toolName();
        AgentToolDefinition definition = registry.findDefinition(toolName);
        if (definition == null) {
            return failure(toolName, "tool not registered", "tool_not_registered", false);
        }
        ToolAuthorizationDecision decision = authorizationPolicy.authorize(definition, invocation);
        if (decision.status() != ToolAuthorizationDecision.Status.ALLOW) {
            String code = decision.status() == ToolAuthorizationDecision.Status.REQUIRE_CONFIRMATION
                    ? "tool_confirmation_required"
                    : "tool_not_authorized";
            return failure(toolName, decision.reason(), code, false);
        }
        ToolExecutionContext context = new ToolExecutionContext(UUID.randomUUID().toString(), definition);
        return new Chain(0).proceed(context, invocation);
    }

    @Override
    public List<Map<String, Object>> listToolSchemas() {
        return registry.listModelVisibleSchemas();
    }

    @Override
    public List<AgentToolDefinition> listToolDefinitions() {
        return registry.listDefinitions();
    }

    @Override
    public AgentToolDefinition getToolDefinition(String name) {
        return registry.findDefinition(name);
    }

    private ToolExecutionResult failure(String tool, String error, String code, boolean retryable) {
        return new ToolExecutionResult(
                tool,
                ToolExecutionStatus.FAILED,
                false,
                List.of(),
                List.of(error),
                retryable,
                code,
                NullNode.getInstance(),
                json.emptyObject());
    }

    private final class Chain implements ToolInvocationChain {
        private final int index;

        private Chain(int index) {
            this.index = index;
        }

        @Override
        public ToolExecutionResult proceed(ToolExecutionContext context, ToolInvocation invocation) {
            if (index >= middlewares.size()) {
                return invoker.invoke(context.definition(), invocation);
            }
            return middlewares.get(index).invoke(context, invocation, new Chain(index + 1));
        }
    }
}
