package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;

/** 对幂等且明确标记为可重试的失败执行有限次数重试。 */
public class RetryToolMiddleware implements ToolMiddleware {

    private final int maxRetries;

    public RetryToolMiddleware(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public ToolExecutionResult invoke(ToolExecutionContext context, ToolInvocation invocation, ToolInvocationChain chain) {
        ToolExecutionResult result = chain.proceed(context, invocation);
        for (int attempt = 0; attempt < maxRetries
                && context.definition().isIdempotent()
                && result.retryable(); attempt++) {
            result = chain.proceed(context, invocation);
        }
        return result;
    }
}
