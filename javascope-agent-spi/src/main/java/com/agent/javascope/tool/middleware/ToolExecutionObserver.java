package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolExecutionResult;

/**
 * 工具调用的低基数可观测出口。实现方可以记录指标或遥测，但不得影响工具执行结果。
 */
public interface ToolExecutionObserver {

    ToolExecutionObserver NOOP = new ToolExecutionObserver() { };

    /** 记录一次实际调用尝试；{@code attemptNumber} 从 1 开始。 */
    default void onAttemptCompleted(
            ToolExecutionContext context,
            int attemptNumber,
            ToolExecutionResult result,
            long durationMs) { }

    /** 记录包含全部自动重试在内的一次逻辑工具调用。 */
    default void onCallCompleted(
            ToolExecutionContext context,
            ToolExecutionResult result,
            int attemptCount,
            boolean retryExhausted,
            long durationMs) { }
}
