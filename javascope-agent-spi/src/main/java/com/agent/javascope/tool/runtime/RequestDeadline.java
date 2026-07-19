package com.agent.javascope.tool.runtime;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 使用单调时钟表达一次 Agent 请求的绝对截止时间。 */
public final class RequestDeadline {

    private final long deadlineNanos;
    private final LongSupplier nanoTime;

    private RequestDeadline(long deadlineNanos, LongSupplier nanoTime) {
        this.deadlineNanos = deadlineNanos;
        this.nanoTime = nanoTime;
    }

    public static RequestDeadline afterMillis(long timeoutMs) {
        return afterMillis(timeoutMs, System::nanoTime);
    }

    static RequestDeadline afterMillis(long timeoutMs, LongSupplier nanoTime) {
        long now = nanoTime.getAsLong();
        long duration = TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        long deadline;
        try {
            deadline = Math.addExact(now, duration);
        } catch (ArithmeticException overflow) {
            deadline = Long.MAX_VALUE;
        }
        return new RequestDeadline(deadline, nanoTime);
    }

    public static RequestDeadline unbounded() {
        return new RequestDeadline(Long.MAX_VALUE, System::nanoTime);
    }

    public long remainingMillis() {
        if (deadlineNanos == Long.MAX_VALUE) return Long.MAX_VALUE;
        long remaining = deadlineNanos - nanoTime.getAsLong();
        if (remaining <= 0) return 0;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    public boolean isExpired() {
        return remainingMillis() == 0;
    }
}
