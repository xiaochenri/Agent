package com.agent.javascope.tool.middleware;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.tool.runtime.RequestDeadline;
import com.agent.javascope.tool.runtime.RetryBudget;
import com.agent.javascope.tool.runtime.ToolErrorCode;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolExecutionStatus;
import com.agent.javascope.tool.runtime.ToolInvocation;
import com.agent.javascope.tool.runtime.ToolRequestContext;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 无测试框架依赖的可靠性验收用例；可直接运行 main。 */
public final class ToolRuntimeReliabilityAcceptanceTest {

    public static void main(String[] args) {
        retriesWhitelistedFailureInSameCall();
        rejectsNonWhitelistedFailure();
        requiresIdempotencyKeyForWriteRetry();
        sharesRetryBudgetAcrossToolCalls();
        enforcesRealAttemptTimeout();
        avoidsRetryWhenSideEffectIsUncertain();
    }

    private static void retriesWhitelistedFailureInSameCall() {
        AtomicInteger calls = new AtomicInteger();
        RetryToolMiddleware retry = retry(2);
        ToolExecutionResult result = retry.invoke(
                context(true, true, 1_000),
                invocation(RetryBudget.limited(2)),
                (context, invocation) -> calls.incrementAndGet() < 3
                        ? failure(ToolErrorCode.TOOL_NETWORK_ERROR, true)
                        : success());

        require(result.isSuccess(), "白名单异常重试后应成功");
        require(calls.get() == 3, "全部尝试必须在同一次中间件调用中完成");
        require(result.metadata().path("attempt_count").asInt() == 3, "attempt_count 应记录全部尝试");
        require("success".equals(result.metadata().path("retry_stop_reason").asText()), "应以成功停止");
    }

    private static void rejectsNonWhitelistedFailure() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionResult result = retry(3).invoke(
                context(true, true, 1_000),
                invocation(RetryBudget.limited(3)),
                (context, invocation) -> {
                    calls.incrementAndGet();
                    return failure(ToolErrorCode.TOOL_INPUT_INVALID, true);
                });

        require(!result.isSuccess(), "非法输入必须失败");
        require(calls.get() == 1, "非法输入即使错误标记 retryable 也不得重试");
        require("non_retryable".equals(result.metadata().path("retry_stop_reason").asText()),
                "非白名单异常应停止重试");
    }

    private static void sharesRetryBudgetAcrossToolCalls() {
        RetryBudget budget = RetryBudget.limited(1);
        AtomicInteger calls = new AtomicInteger();
        ToolInvocation invocation = invocation(budget);
        ToolInvocationChain alwaysFails = (context, ignored) -> {
            calls.incrementAndGet();
            return failure(ToolErrorCode.TOOL_DEPENDENCY_UNAVAILABLE, true);
        };

        ToolExecutionResult first = retry(3).invoke(context(true, true, 1_000), invocation, alwaysFails);
        ToolExecutionResult second = retry(3).invoke(context(true, true, 1_000), invocation, alwaysFails);

        require(calls.get() == 3, "共享预算为 1 时，两次工具调用总计只能多执行一次");
        require("retry_budget_exhausted".equals(first.metadata().path("retry_stop_reason").asText()),
                "首次调用应耗尽请求预算");
        require(second.metadata().path("attempt_count").asInt() == 1, "后续调用不得获得新的重试预算");
    }

    private static void requiresIdempotencyKeyForWriteRetry() {
        AtomicInteger withoutKeyCalls = new AtomicInteger();
        ToolExecutionResult withoutKey = retry(2).invoke(
                context(true, false, 1_000),
                invocation(RetryBudget.limited(2)),
                (context, invocation) -> {
                    withoutKeyCalls.incrementAndGet();
                    return failure(ToolErrorCode.TOOL_NETWORK_ERROR, true);
                });
        require(withoutKeyCalls.get() == 1, "写工具没有幂等键时不得自动重试");
        require(withoutKey.metadata().path("retry_skipped").asBoolean(), "应记录因安全约束跳过重试");

        AtomicInteger withKeyCalls = new AtomicInteger();
        ToolInvocation keyed = new ToolInvocation(
                "acceptance_tool", JsonNodeFactory.instance.objectNode(), "{}", "operation-1",
                new ToolRequestContext(
                        "execution-1", RequestDeadline.afterMillis(10_000), RetryBudget.limited(1)));
        retry(1).invoke(
                context(true, false, 1_000),
                keyed,
                (context, invocation) -> {
                    withKeyCalls.incrementAndGet();
                    return failure(ToolErrorCode.TOOL_NETWORK_ERROR, true);
                });
        require(withKeyCalls.get() == 2, "声明幂等且携带幂等键的写工具可按白名单重试");
    }

    private static void enforcesRealAttemptTimeout() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            TimeLimitToolMiddleware timeout = new TimeLimitToolMiddleware(executor);
            ToolInvocationChain slow = (context, invocation) -> {
                calls.incrementAndGet();
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                return success();
            };
            ToolExecutionResult result = retry(1).invoke(
                    context(true, true, 20),
                    invocation(RetryBudget.limited(1)),
                    (context, invocation) -> timeout.invoke(context, invocation, slow));

            require(!result.isSuccess(), "超过 timeoutMs 的工具必须真实超时");
            require(calls.get() == 2, "只读幂等工具的超时属于白名单，应在同一轮重试一次");
            require(result.error().category().name().equals("TIMEOUT"), "超时必须归一为 TIMEOUT");
        }
    }

    private static void avoidsRetryWhenSideEffectIsUncertain() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            TimeLimitToolMiddleware timeout = new TimeLimitToolMiddleware(executor);
            ToolInvocationChain slowWrite = (context, invocation) -> {
                calls.incrementAndGet();
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                return success();
            };
            ToolExecutionResult result = retry(3).invoke(
                    context(true, false, 20),
                    invocation(RetryBudget.limited(3)),
                    (context, invocation) -> timeout.invoke(context, invocation, slowWrite));

            require(calls.get() == 1, "有副作用工具超时后不得自动重试");
            require(result.error().code().equals(ToolErrorCode.TOOL_SIDE_EFFECT_UNCERTAIN.code()),
                    "有副作用工具超时必须标记结果不确定");
        }
    }

    private static RetryToolMiddleware retry(int maxRetries) {
        return new RetryToolMiddleware(
                maxRetries, 0, 0, DefaultToolRetryPolicy.INSTANCE, ToolExecutionObserver.NOOP,
                millis -> { }, bound -> 0);
    }

    private static ToolExecutionContext context(boolean idempotent, boolean readOnly, int timeoutMs) {
        AgentToolDefinition definition = new AgentToolDefinition();
        definition.setName("acceptance_tool");
        definition.setIdempotent(idempotent);
        definition.setReadOnly(readOnly);
        definition.setTimeoutMs(timeoutMs);
        return new ToolExecutionContext("invocation-1", definition);
    }

    private static ToolInvocation invocation(RetryBudget budget) {
        return new ToolInvocation(
                "acceptance_tool",
                JsonNodeFactory.instance.objectNode(),
                "{}",
                new ToolRequestContext("execution-1", RequestDeadline.afterMillis(10_000), budget));
    }

    private static ToolExecutionResult failure(ToolErrorCode code, boolean retryable) {
        return ToolResultFactory.failed("acceptance_tool", code, "safe failure", retryable);
    }

    private static ToolExecutionResult success() {
        return new ToolExecutionResult(
                "acceptance_tool", ToolExecutionStatus.SUCCESS, true, List.of(), List.of(),
                false, "", JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(), null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
