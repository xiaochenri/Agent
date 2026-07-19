package com.agent.javascope.tool.runtime;

import java.util.concurrent.atomic.AtomicInteger;

/** 一次 Agent 请求内全部工具共享的自动重试令牌预算；首次调用不消耗令牌。 */
public final class RetryBudget {

    private final boolean unlimited;
    private final AtomicInteger remaining;

    private RetryBudget(int tokens, boolean unlimited) {
        this.unlimited = unlimited;
        this.remaining = new AtomicInteger(Math.max(0, tokens));
    }

    public static RetryBudget limited(int tokens) {
        return new RetryBudget(tokens, false);
    }

    public static RetryBudget unlimited() {
        return new RetryBudget(Integer.MAX_VALUE, true);
    }

    public boolean tryAcquireRetry() {
        if (unlimited) return true;
        while (true) {
            int current = remaining.get();
            if (current <= 0) return false;
            if (remaining.compareAndSet(current, current - 1)) return true;
        }
    }

    public int remaining() {
        return unlimited ? Integer.MAX_VALUE : remaining.get();
    }
}
