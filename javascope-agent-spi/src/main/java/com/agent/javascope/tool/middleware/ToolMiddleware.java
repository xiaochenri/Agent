package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;

/** 工具执行过程中的横切治理能力。 */
public interface ToolMiddleware {

    ToolExecutionResult invoke(ToolExecutionContext context, ToolInvocation invocation, ToolInvocationChain chain);
}
