package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;

/** 将请求传递给下一个 Middleware 或最终 Invoker 的链。 */
public interface ToolInvocationChain {

    ToolExecutionResult proceed(ToolExecutionContext context, ToolInvocation invocation);
}
