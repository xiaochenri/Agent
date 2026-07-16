package com.agent.javascope.agent.runtime;

import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.tool.runtime.ToolError;
import com.agent.javascope.tool.runtime.ToolErrorCategory;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolErrorCode;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Map;

/** 统一维护工具失败的记录、调用阻断、工具降级与成功恢复。 */
public final class ToolFailureTracker {

    private static final int MAX_ACTIVE_FAILURES = 20;

    private ToolFailureTracker() {}

    /** 以执行器最终结果更新恢复状态；中间件内部重试不会形成重复失败记录。 */
    public static void recordResult(
            RuntimeState state,
            int round,
            String sourceStep,
            String toolName,
            Map<String, Object> input,
            ToolExecutionResult result,
            AgentJsonCodecUtil json) {
        String fingerprint = fingerprint(toolName, input, json);
        if (result.isSuccess()) {
            resolveSuccessfulCall(state, toolName, fingerprint);
            return;
        }
        int attemptCount = readAttemptCount(result.metadata());
        recordFailure(state, round, sourceStep, toolName, fingerprint, result.error(), attemptCount);
    }

    /** 记录执行前策略拒绝、计划依赖阻塞等未进入工具执行器的结构化失败。 */
    public static void recordFailure(
            RuntimeState state,
            int round,
            String sourceStep,
            String toolName,
            Map<String, Object> input,
            ToolError error,
            int attemptCount,
            AgentJsonCodecUtil json) {
        recordFailure(state, round, sourceStep, toolName,
                fingerprint(toolName, input, json), error, attemptCount);
    }

    /** 生成所有模式共用的规范化 tool+input 指纹。 */
    public static String fingerprint(
            String toolName, Map<String, Object> input, AgentJsonCodecUtil json) {
        return json.normalize(toolName, "") + "|" + json.toJson(input == null ? Map.of() : input);
    }

    /** 成功重规划后解除旧计划的前置条件失败；真实工具执行失败仍保持活跃。 */
    public static void resolvePlanPreconditions(RuntimeState state) {
        for (String fingerprint : new ArrayList<>(state.activeToolFailures.keySet())) {
            ToolFailureRecord record = state.activeToolFailures.get(fingerprint);
            if (record != null && ToolErrorCode.PLAN_PRECONDITION_FAILED.code()
                    .equals(record.error().code())) {
                state.activeToolFailures.remove(fingerprint);
                state.blockedActionFingerprints.remove(fingerprint);
            }
        }
    }

    private static void recordFailure(
            RuntimeState state,
            int round,
            String sourceStep,
            String toolName,
            String fingerprint,
            ToolError error,
            int attemptCount) {
        ToolFailureRecord record = new ToolFailureRecord(
                "tool_failure_" + (state.activeToolFailureSequence++),
                round,
                sourceStep == null ? "" : sourceStep,
                toolName == null ? "" : toolName,
                fingerprint,
                error,
                Math.max(1, attemptCount),
                true,
                true);
        state.activeToolFailures.remove(fingerprint);
        state.activeToolFailures.put(fingerprint, record);
        state.blockedActionFingerprints.add(fingerprint);
        if (isToolUnavailable(error.category())) state.unavailableTools.add(toolName);
        evictOldestFailure(state);
    }

    private static void resolveSuccessfulCall(
            RuntimeState state, String toolName, String successfulFingerprint) {
        state.activeToolFailures.remove(successfulFingerprint);
        state.blockedActionFingerprints.remove(successfulFingerprint);
        if (!state.unavailableTools.remove(toolName)) return;

        // 一次真实成功调用证明依赖已经恢复；解除同工具的依赖/熔断失败，但保留输入类失败。
        for (String fingerprint : new ArrayList<>(state.activeToolFailures.keySet())) {
            ToolFailureRecord record = state.activeToolFailures.get(fingerprint);
            if (record != null && toolName.equals(record.toolName())
                    && isToolUnavailable(record.error().category())) {
                state.activeToolFailures.remove(fingerprint);
                state.blockedActionFingerprints.remove(fingerprint);
            }
        }
    }

    private static boolean isToolUnavailable(ToolErrorCategory category) {
        return category == ToolErrorCategory.DEPENDENCY_UNAVAILABLE
                || category == ToolErrorCategory.CIRCUIT_OPEN;
    }

    private static int readAttemptCount(JsonNode metadata) {
        return metadata == null ? 1 : metadata.path("attempt_count").asInt(1);
    }

    private static void evictOldestFailure(RuntimeState state) {
        while (state.activeToolFailures.size() > MAX_ACTIVE_FAILURES) {
            String oldest = state.activeToolFailures.keySet().iterator().next();
            state.activeToolFailures.remove(oldest);
            state.blockedActionFingerprints.remove(oldest);
        }
    }
}
