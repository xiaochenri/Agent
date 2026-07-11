package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 简单的单进程固定窗口限流实现，适合作为可替换的默认保护。 */
public class RateLimitToolMiddleware implements ToolMiddleware {

    private final int maxRequestsPerMinute;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitToolMiddleware(int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
    }

    @Override
    public ToolExecutionResult invoke(ToolExecutionContext context, ToolInvocation invocation, ToolInvocationChain chain) {
        Window window = windows.compute(context.definition().getName(), (ignored, current) -> {
            long now = System.currentTimeMillis();
            if (current == null || now - current.startedAt > 60_000) {
                return new Window(now);
            }
            return current;
        });
        if (window.count.incrementAndGet() > maxRequestsPerMinute) {
            return ToolResultFactory.failed(
                    invocation.toolName(), "tool_rate_limited", "tool rate limit exceeded", true);
        }
        return chain.proceed(context, invocation);
    }

    private static final class Window {
        private final long startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
