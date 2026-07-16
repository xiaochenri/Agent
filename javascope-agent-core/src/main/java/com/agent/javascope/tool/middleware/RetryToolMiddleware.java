package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.RecoveryAction;
import com.agent.javascope.tool.runtime.RecoveryOwner;
import com.agent.javascope.tool.runtime.ToolError;
import com.agent.javascope.tool.runtime.ToolErrorCategory;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 对幂等且明确标记为可重试的失败执行有限次数重试。 */
public class RetryToolMiddleware implements ToolMiddleware {

    private final int maxRetries;

    public RetryToolMiddleware(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public ToolExecutionResult invoke(ToolExecutionContext context, ToolInvocation invocation, ToolInvocationChain chain) {
        ToolExecutionResult result = chain.proceed(context, invocation);
        int retries = 0;
        int maxAttempts = context.definition().isIdempotent() ? maxRetries + 1 : 1;
        for (; retries < maxRetries
                && context.definition().isIdempotent()
                && isTransientRetry(result); retries++) {
            result = chain.proceed(context, invocation);
        }
        return finalResult(result, retries + 1, maxAttempts, context.definition().isIdempotent());
    }

    private boolean isTransientRetry(ToolExecutionResult result) {
        if (result.isSuccess() || result.error() == null || !result.error().retryable()) return false;
        return switch (result.error().category()) {
            case RATE_LIMITED, TIMEOUT, NETWORK_ERROR, DEPENDENCY_UNAVAILABLE, BULKHEAD_REJECTED -> true;
            default -> false;
        };
    }

    private ToolExecutionResult finalResult(
            ToolExecutionResult result, int attemptCount, int maxAttempts, boolean retryAllowed) {
        if (result.isSuccess()) return withAttemptMetadata(result, attemptCount, maxAttempts, false, false);
        if (result.error() == null || !isTransient(result.error().category())) {
            return withAttemptMetadata(result, attemptCount, maxAttempts, false, false);
        }
        ToolError error = result.error();
        ToolError exhausted = new ToolError(
                error.category(), error.code(), error.publicMessage(), RecoveryOwner.MODEL,
                List.of(RecoveryAction.USE_ALTERNATIVE_TOOL, RecoveryAction.FINALIZE_WITH_LIMITATION),
                error.retryable(), error.retryAfterMs(), error.exceptionType(), error.detailsRef());
        ToolExecutionResult normalized = new ToolExecutionResult(
                result.toolName(), result.status(), result.validationPassed(), result.validationRules(),
                result.validationErrors(), exhausted.retryable(), exhausted.code(), result.data(), result.metadata(), exhausted);
        return withAttemptMetadata(normalized, attemptCount, maxAttempts, retryAllowed, !retryAllowed);
    }

    private boolean isTransient(ToolErrorCategory category) {
        return switch (category) {
            case RATE_LIMITED, TIMEOUT, NETWORK_ERROR, DEPENDENCY_UNAVAILABLE, BULKHEAD_REJECTED -> true;
            default -> false;
        };
    }

    private ToolExecutionResult withAttemptMetadata(
            ToolExecutionResult result,
            int attemptCount,
            int maxAttempts,
            boolean retryExhausted,
            boolean retrySkipped) {
        ObjectNode metadata = result.metadata().isObject()
                ? ((ObjectNode) result.metadata()).deepCopy()
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        metadata.put("attempt_count", attemptCount);
        metadata.put("max_attempts", maxAttempts);
        metadata.put("retry_exhausted", retryExhausted);
        metadata.put("retry_skipped", retrySkipped);
        return new ToolExecutionResult(
                result.toolName(), result.status(), result.validationPassed(), result.validationRules(),
                result.validationErrors(), result.retryable(), result.errorCode(), result.data(), metadata, result.error());
    }
}
