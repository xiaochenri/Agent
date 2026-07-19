package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolErrorCategory;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;
import java.util.EnumSet;
import java.util.Set;

/** 默认只重试幂等工具的明确瞬时基础设施错误。 */
public final class DefaultToolRetryPolicy implements ToolRetryPolicy {

    public static final DefaultToolRetryPolicy INSTANCE = new DefaultToolRetryPolicy();

    private static final Set<ToolErrorCategory> RETRYABLE_CATEGORIES = EnumSet.of(
            ToolErrorCategory.RATE_LIMITED,
            ToolErrorCategory.TIMEOUT,
            ToolErrorCategory.NETWORK_ERROR,
            ToolErrorCategory.DEPENDENCY_UNAVAILABLE,
            ToolErrorCategory.BULKHEAD_REJECTED);

    private DefaultToolRetryPolicy() { }

    @Override
    public boolean shouldRetry(
            ToolExecutionContext context,
            ToolInvocation invocation,
            ToolExecutionResult result) {
        return context != null
                && context.definition() != null
                && context.definition().isIdempotent()
                && (context.definition().isReadOnly()
                    || invocation != null && !invocation.idempotencyKey().isBlank())
                && result != null
                && !result.isSuccess()
                && result.error() != null
                && result.error().retryable()
                && RETRYABLE_CATEGORIES.contains(result.error().category());
    }
}
