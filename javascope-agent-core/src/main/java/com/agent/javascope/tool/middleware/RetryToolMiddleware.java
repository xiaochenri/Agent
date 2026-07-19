package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.error.DefaultToolErrorClassifier;
import com.agent.javascope.tool.runtime.RecoveryAction;
import com.agent.javascope.tool.runtime.RecoveryOwner;
import com.agent.javascope.tool.runtime.RequestDeadline;
import com.agent.javascope.tool.runtime.RetryBudget;
import com.agent.javascope.tool.runtime.ToolError;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;
import com.agent.javascope.tool.runtime.ToolRequestContext;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 在一次逻辑工具调用内部完成瞬时错误重试、退避和请求级预算控制。
 * 业务代码不参与错误白名单、等待或重试次数决策。
 */
public class RetryToolMiddleware implements ToolMiddleware {

    private static final System.Logger LOG = System.getLogger(RetryToolMiddleware.class.getName());

    private final int maxRetries;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final ToolRetryPolicy retryPolicy;
    private final ToolExecutionObserver observer;
    private final Sleeper sleeper;
    private final JitterSource jitter;

    public RetryToolMiddleware(int maxRetries) {
        this(maxRetries, 100, 3000, DefaultToolRetryPolicy.INSTANCE, ToolExecutionObserver.NOOP);
    }

    public RetryToolMiddleware(int maxRetries, ToolExecutionObserver observer) {
        this(maxRetries, 100, 3000, DefaultToolRetryPolicy.INSTANCE, observer);
    }

    public RetryToolMiddleware(
            int maxRetries,
            long baseDelayMs,
            long maxDelayMs,
            ToolRetryPolicy retryPolicy,
            ToolExecutionObserver observer) {
        this(maxRetries, baseDelayMs, maxDelayMs, retryPolicy, observer,
                Thread::sleep, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    RetryToolMiddleware(
            int maxRetries,
            long baseDelayMs,
            long maxDelayMs,
            ToolRetryPolicy retryPolicy,
            ToolExecutionObserver observer,
            Sleeper sleeper,
            JitterSource jitter) {
        this.maxRetries = Math.max(0, maxRetries);
        this.baseDelayMs = Math.max(0, baseDelayMs);
        this.maxDelayMs = Math.max(0, maxDelayMs);
        this.retryPolicy = retryPolicy == null ? DefaultToolRetryPolicy.INSTANCE : retryPolicy;
        this.observer = observer == null ? ToolExecutionObserver.NOOP : observer;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
        this.jitter = jitter == null
                ? bound -> ThreadLocalRandom.current().nextLong(bound) : jitter;
    }

    @Override
    public ToolExecutionResult invoke(
            ToolExecutionContext context, ToolInvocation invocation, ToolInvocationChain chain) {
        long callStartedAt = System.nanoTime();
        ToolRequestContext request = invocation == null
                ? ToolRequestContext.unbounded() : invocation.requestContext();
        RequestDeadline deadline = request.deadline();
        RetryBudget budget = request.retryBudget();
        boolean retryCapable = context.definition().isIdempotent()
                && (context.definition().isReadOnly()
                    || invocation != null && !invocation.idempotencyKey().isBlank());
        int maxAttempts = retryCapable ? maxRetries + 1 : 1;
        int attemptCount = 1;
        long totalBackoffMs = 0;
        String stopReason = "non_retryable";
        List<AttemptRecord> attempts = new ArrayList<>();

        AttemptRecord current = invokeAttempt(context, invocation, chain, attemptCount);
        attempts.add(current);
        ToolExecutionResult result = current.result();

        while (true) {
            if (result.isSuccess()) {
                stopReason = "success";
                break;
            }
            if (!retryPolicy.shouldRetry(context, invocation, result)) {
                stopReason = "non_retryable";
                break;
            }
            if (attemptCount >= maxAttempts) {
                stopReason = "max_attempts_exhausted";
                break;
            }
            if (deadline.isExpired()) {
                stopReason = "request_deadline_exceeded";
                break;
            }

            long backoffMs = computeBackoffMs(result.error(), attemptCount);
            long remainingMs = deadline.remainingMillis();
            if (remainingMs != Long.MAX_VALUE && backoffMs >= remainingMs) {
                stopReason = "request_deadline_exceeded";
                break;
            }
            if (!budget.tryAcquireRetry()) {
                stopReason = "retry_budget_exhausted";
                break;
            }
            current.nextBackoffMs(backoffMs);
            try {
                sleeper.sleep(backoffMs);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                stopReason = "interrupted";
                result = ToolResultFactory.failed(
                        result.toolName(), DefaultToolErrorClassifier.INSTANCE.classify(error));
                break;
            }
            totalBackoffMs += backoffMs;
            attemptCount++;
            current = invokeAttempt(context, invocation, chain, attemptCount);
            attempts.add(current);
            result = current.result();
        }

        boolean retryExhausted = retryPolicy.shouldRetry(context, invocation, result)
                && ("max_attempts_exhausted".equals(stopReason)
                || "retry_budget_exhausted".equals(stopReason)
                || "request_deadline_exceeded".equals(stopReason));
        boolean retrySkipped = result != null
                && result.error() != null
                && result.error().retryable()
                && !retryCapable;
        ToolExecutionResult normalized = normalizeFinalError(result, retryExhausted);
        return withAttemptMetadata(
                normalized,
                attemptCount,
                maxAttempts,
                retryExhausted,
                retrySkipped,
                elapsedMs(callStartedAt),
                totalBackoffMs,
                stopReason,
                budget.remaining(),
                deadline.remainingMillis(),
                attempts);
    }

    private AttemptRecord invokeAttempt(
            ToolExecutionContext context,
            ToolInvocation invocation,
            ToolInvocationChain chain,
            int attemptNumber) {
        long startedAt = System.nanoTime();
        ToolExecutionResult result;
        try {
            result = chain.proceed(context, invocation);
        } catch (RuntimeException error) {
            result = ToolResultFactory.failed(
                    invocation == null ? "" : invocation.toolName(),
                    DefaultToolErrorClassifier.INSTANCE.classify(error));
        }
        if (result == null) {
            result = ToolResultFactory.failed(
                    invocation == null ? "" : invocation.toolName(),
                    com.agent.javascope.tool.runtime.ToolErrorCode.TOOL_RESULT_NULL,
                    "工具未返回执行结果",
                    false);
        }
        long durationMs = elapsedMs(startedAt);
        safelyObserveAttempt(context, attemptNumber, result, durationMs);
        return new AttemptRecord(attemptNumber, result, durationMs);
    }

    private long computeBackoffMs(ToolError error, int completedAttempts) {
        if (error != null && error.retryAfterMs() != null) {
            return Math.min(maxDelayMs, Math.max(0, error.retryAfterMs()));
        }
        long cap = exponentialCap(completedAttempts);
        if (cap <= 0) return 0;
        long bound = cap == Long.MAX_VALUE ? Long.MAX_VALUE : cap + 1;
        return jitter.nextLong(bound);
    }

    private long exponentialCap(int completedAttempts) {
        if (baseDelayMs <= 0 || maxDelayMs <= 0) return 0;
        int shift = Math.max(0, Math.min(30, completedAttempts - 1));
        long candidate = baseDelayMs > (Long.MAX_VALUE >> shift)
                ? Long.MAX_VALUE : baseDelayMs << shift;
        return Math.min(maxDelayMs, candidate);
    }

    private ToolExecutionResult normalizeFinalError(ToolExecutionResult result, boolean retryExhausted) {
        if (result == null || result.isSuccess() || result.error() == null || !retryExhausted) return result;
        ToolError error = result.error();
        ToolError exhausted = new ToolError(
                error.category(),
                error.code(),
                error.publicMessage(),
                RecoveryOwner.MODEL,
                List.of(RecoveryAction.USE_ALTERNATIVE_TOOL, RecoveryAction.FINALIZE_WITH_LIMITATION),
                error.retryable(),
                error.retryAfterMs(),
                error.exceptionType(),
                error.detailsRef());
        return new ToolExecutionResult(
                result.toolName(), result.status(), result.validationPassed(), result.validationRules(),
                result.validationErrors(), exhausted.retryable(), exhausted.code(),
                result.data(), result.metadata(), exhausted);
    }

    private ToolExecutionResult withAttemptMetadata(
            ToolExecutionResult result,
            int attemptCount,
            int maxAttempts,
            boolean retryExhausted,
            boolean retrySkipped,
            long totalDurationMs,
            long totalBackoffMs,
            String stopReason,
            int budgetRemaining,
            long requestRemainingMs,
            List<AttemptRecord> attempts) {
        ObjectNode metadata = result.metadata().isObject()
                ? ((ObjectNode) result.metadata()).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        metadata.put("attempt_count", attemptCount);
        metadata.put("max_attempts", maxAttempts);
        metadata.put("retry_exhausted", retryExhausted);
        metadata.put("retry_skipped", retrySkipped);
        metadata.put("retry_stop_reason", stopReason);
        metadata.put("retry_budget_remaining", budgetRemaining);
        metadata.put("total_backoff_ms", Math.max(0, totalBackoffMs));
        metadata.put("total_duration_ms", Math.max(0, totalDurationMs));
        metadata.put("request_remaining_ms", requestRemainingMs);
        ArrayNode summaries = metadata.putArray("attempts");
        for (AttemptRecord attempt : attempts) summaries.add(attempt.toJson());
        return new ToolExecutionResult(
                result.toolName(), result.status(), result.validationPassed(), result.validationRules(),
                result.validationErrors(), result.retryable(), result.errorCode(),
                result.data(), metadata, result.error());
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private void safelyObserveAttempt(
            ToolExecutionContext context,
            int attemptNumber,
            ToolExecutionResult result,
            long durationMs) {
        try {
            observer.onAttemptCompleted(context, attemptNumber, result, durationMs);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "Tool attempt observer failed", error);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface JitterSource {
        long nextLong(long boundExclusive);
    }

    private static final class AttemptRecord {
        private final int attemptNumber;
        private final ToolExecutionResult result;
        private final long durationMs;
        private long nextBackoffMs;

        private AttemptRecord(int attemptNumber, ToolExecutionResult result, long durationMs) {
            this.attemptNumber = attemptNumber;
            this.result = result;
            this.durationMs = durationMs;
        }

        private ToolExecutionResult result() {
            return result;
        }

        private void nextBackoffMs(long value) {
            nextBackoffMs = Math.max(0, value);
        }

        private ObjectNode toJson() {
            ObjectNode summary = JsonNodeFactory.instance.objectNode();
            summary.put("attempt", attemptNumber);
            summary.put("duration_ms", durationMs);
            summary.put("status", result != null && result.isSuccess() ? "success" : "failed");
            summary.put("next_backoff_ms", nextBackoffMs);
            if (result != null && result.error() != null) {
                summary.put("error_category", result.error().category().name());
                summary.put("error_code", result.error().code());
            }
            return summary;
        }
    }
}
