package com.agent.javascope.agent.support;

import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.tool.middleware.ToolResultFactory;
import com.agent.javascope.tool.runtime.ToolExecutionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将强类型工具结果映射为历史执行日志和业务兼容层使用的稳定 JSON 结构。
 *
 * <p>状态机本身必须使用 {@link ToolExecutionResult#isSuccess()} 判断成败；本类只解决旧日志、
 * Prompt 和业务 DTO 仍需要 {@code tool}/{@code success|failed} 字段的问题。</p>
 */
public final class ToolExecutionResultMapper {

    private ToolExecutionResultMapper() {}

    /** 将结果转换为兼容协议，避免枚举序列化为 {@code SUCCESS}/{@code FAILED} 泄漏到旧消费者。 */
    public static Map<String, Object> toCompatibilityMap(ToolExecutionResult result, AgentJsonCodecUtil json) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("tool", result.toolName());
        mapped.put("status", result.isSuccess() ? "success" : "failed");
        mapped.put("validation_passed", result.validationPassed());
        mapped.put("validation_rules", List.copyOf(result.validationRules()));
        mapped.put("validation_errors", List.copyOf(result.validationErrors()));
        mapped.put("retryable", result.retryable());
        mapped.put("error_code", result.errorCode());
        if (result.error() != null) {
            mapped.put("error", ToolResultFactory.publicError(result.error()));
        }
        mapped.put("data", json.convert(result.data(), Object.class));
        mapped.put("metadata", json.convert(result.metadata(), Object.class));
        return mapped;
    }
}
