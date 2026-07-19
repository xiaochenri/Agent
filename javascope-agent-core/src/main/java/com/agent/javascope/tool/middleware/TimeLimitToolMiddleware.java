package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.error.DefaultToolErrorClassifier;
import com.agent.javascope.tool.runtime.ToolErrorCode;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 对每一次实际工具 Attempt 落实 timeoutMs，并受 Agent 请求剩余时间约束。 */
public final class TimeLimitToolMiddleware implements ToolMiddleware {

    private final ExecutorService executor;

    public TimeLimitToolMiddleware(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public ToolExecutionResult invoke(
            ToolExecutionContext context, ToolInvocation invocation, ToolInvocationChain chain) {
        String toolName = invocation == null ? "" : invocation.toolName();
        long requestRemainingMs = invocation == null
                ? Long.MAX_VALUE : invocation.requestContext().deadline().remainingMillis();
        if (requestRemainingMs == 0) {
            return ToolResultFactory.failed(
                    toolName,
                    ToolErrorCode.TOOL_REQUEST_DEADLINE_EXCEEDED,
                    "Agent 请求时间预算已耗尽",
                    true);
        }
        long toolTimeoutMs = Math.max(1, context.definition().getTimeoutMs());
        long effectiveTimeoutMs = requestRemainingMs == Long.MAX_VALUE
                ? toolTimeoutMs : Math.max(1, Math.min(toolTimeoutMs, requestRemainingMs));
        Future<ToolExecutionResult> future;
        try {
            future = executor.submit(() -> chain.proceed(context, invocation));
        } catch (RejectedExecutionException error) {
            return ToolResultFactory.failed(
                    toolName, ToolErrorCode.TOOL_BULKHEAD_REJECTED,
                    "工具执行资源暂时不可用", true);
        }
        try {
            return future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            if (!context.definition().isReadOnly()) {
                return ToolResultFactory.failed(
                        toolName,
                        ToolErrorCode.TOOL_SIDE_EFFECT_UNCERTAIN,
                        "工具调用超时，无法确认外部操作是否完成",
                        false);
            }
            ToolErrorCode code = requestRemainingMs != Long.MAX_VALUE
                    && requestRemainingMs <= toolTimeoutMs
                    ? ToolErrorCode.TOOL_REQUEST_DEADLINE_EXCEEDED
                    : ToolErrorCode.TOOL_TIMEOUT;
            String message = code == ToolErrorCode.TOOL_REQUEST_DEADLINE_EXCEEDED
                    ? "Agent 请求时间预算已耗尽" : "工具调用超时";
            return ToolResultFactory.failed(toolName, code, message, true);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ToolResultFactory.failed(
                    toolName, DefaultToolErrorClassifier.INSTANCE.classify(error));
        } catch (CancellationException error) {
            return ToolResultFactory.failed(
                    toolName, DefaultToolErrorClassifier.INSTANCE.classify(error));
        } catch (ExecutionException error) {
            return ToolResultFactory.failed(
                    toolName, DefaultToolErrorClassifier.INSTANCE.classify(error));
        }
    }
}
