package com.agent.javascope.agent.runtime;

import com.agent.javascope.tool.runtime.ToolError;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次尚未解除的最终工具失败。
 *
 * <p>记录仅保存可安全进入模型上下文的信息；异常类型、内部详情引用和原始失败数据
 * 继续只保存在执行日志与 Trace 中。</p>
 *
 * @param failureId 当前任务内稳定的失败标识
 * @param round 失败发生的推理轮次
 * @param sourceStep 产生失败的执行步骤
 * @param toolName 工具注册名称
 * @param inputFingerprint tool+input 指纹，用于精确阻止相同失败调用
 * @param error 统一结构化错误协议
 * @param attemptCount 中间件完成后的总尝试次数
 * @param finalFailure 是否已完成系统级自动重试并形成最终失败
 * @param blockedSameCall 是否禁止模型再次提交相同 tool+input
 */
public record ToolFailureRecord(
        String failureId,
        int round,
        String sourceStep,
        String toolName,
        String inputFingerprint,
        ToolError error,
        int attemptCount,
        boolean finalFailure,
        boolean blockedSameCall) {

    /** 返回可进入 active_tool_failures 区域的公开投影。 */
    public Map<String, Object> toPublicMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("failure_id", failureId);
        value.put("round", round);
        value.put("source_step", sourceStep);
        value.put("tool_name", toolName);
        value.put("input_fingerprint", inputFingerprint);
        value.put("category", error.category().name());
        value.put("code", error.code());
        value.put("public_message", error.publicMessage());
        value.put("recovery_owner", error.recoveryOwner().name());
        value.put("allowed_actions", error.allowedActions().stream().map(Enum::name).toList());
        value.put("retryable", error.retryable());
        if (error.retryAfterMs() != null) value.put("retry_after_ms", error.retryAfterMs());
        value.put("attempt_count", attemptCount);
        value.put("final_failure", finalFailure);
        value.put("blocked_same_call", blockedSameCall);
        return value;
    }
}
