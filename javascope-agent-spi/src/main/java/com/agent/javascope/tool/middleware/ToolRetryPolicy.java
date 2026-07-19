package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;

/** 框架级自动重试策略；实现不得依赖具体业务工具名或业务错误码。 */
public interface ToolRetryPolicy {

    /** 判断当前最终错误是否允许在同一次工具调用内部再次尝试。 */
    boolean shouldRetry(
            ToolExecutionContext context,
            ToolInvocation invocation,
            ToolExecutionResult result);
}
