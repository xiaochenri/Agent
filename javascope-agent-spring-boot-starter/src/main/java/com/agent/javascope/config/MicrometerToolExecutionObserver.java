package com.agent.javascope.config;

import com.agent.javascope.tool.middleware.ToolExecutionContext;
import com.agent.javascope.tool.middleware.ToolExecutionObserver;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/** 使用 Micrometer 发布工具调用、失败、重试及耗时指标。 */
final class MicrometerToolExecutionObserver implements ToolExecutionObserver {

    private final MeterRegistry registry;

    MicrometerToolExecutionObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onAttemptCompleted(
            ToolExecutionContext context,
            int attemptNumber,
            ToolExecutionResult result,
            long durationMs) {
        String tool = tool(context);
        String category = category(result);
        registry.counter("agent.tool.attempts", "tool", tool, "status", status(result)).increment();
        if (attemptNumber > 1) {
            registry.counter("agent.tool.retry.attempts", "tool", tool, "error.category", category).increment();
        }
        Timer.builder("agent.tool.attempt.latency")
                .description("Duration of one physical tool attempt")
                .tags("tool", tool, "status", status(result))
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    @Override
    public void onCallCompleted(
            ToolExecutionContext context,
            ToolExecutionResult result,
            int attemptCount,
            boolean retryExhausted,
            long durationMs) {
        String tool = tool(context);
        String status = status(result);
        registry.counter("agent.tool.calls", "tool", tool, "status", status).increment();
        Timer.builder("agent.tool.call.latency")
                .description("Duration of one logical tool call including retries")
                .tags("tool", tool, "status", status)
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
        if (!result.isSuccess()) {
            registry.counter("agent.tool.failures",
                    "tool", tool,
                    "error.category", category(result),
                    "error.code", errorCode(result)).increment();
        }
        if (retryExhausted) {
            registry.counter("agent.tool.retry.exhausted",
                    "tool", tool,
                    "error.category", category(result)).increment();
        }
    }

    private String tool(ToolExecutionContext context) {
        if (context == null) return "none";
        return normalize(context.definition() == null
                ? context.invocationId() : context.definition().getName());
    }

    private String status(ToolExecutionResult result) {
        return result != null && result.isSuccess() ? "success" : "failed";
    }

    private String category(ToolExecutionResult result) {
        return normalize(result == null || result.error() == null
                ? null : result.error().category().name());
    }

    private String errorCode(ToolExecutionResult result) {
        return normalize(result == null ? null : result.errorCode());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
