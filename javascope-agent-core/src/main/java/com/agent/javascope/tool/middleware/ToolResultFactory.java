package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.error.DefaultToolErrorClassifier;
import com.agent.javascope.tool.runtime.ToolError;
import com.agent.javascope.tool.runtime.ToolErrorCode;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolExecutionStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Middleware 生成统一失败结果的内部辅助类。 */
public final class ToolResultFactory {

    private ToolResultFactory() {}

    public static ToolExecutionResult failed(String tool, String code, String message, boolean retryable) {
        return failed(tool, DefaultToolErrorClassifier.INSTANCE.classify(code, message, retryable));
    }

    /** 使用框架内置错误码生成失败结果。 */
    public static ToolExecutionResult failed(
            String tool, ToolErrorCode code, String message, boolean retryable) {
        return failed(tool, DefaultToolErrorClassifier.INSTANCE.classify(code, message, retryable));
    }

    public static ToolExecutionResult failed(String tool, ToolError error) {
        return new ToolExecutionResult(
                tool,
                ToolExecutionStatus.FAILED,
                false,
                List.of(),
                List.of(error.publicMessage()),
                error.retryable(),
                error.code(),
                NullNode.getInstance(),
                JsonNodeFactory.instance.objectNode(),
                error);
    }

    /** 输出可进入 Prompt 的安全错误视图，排除异常类型和内部详情引用。 */
    public static Map<String, Object> publicError(ToolError error) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("category", error.category().name());
        mapped.put("code", error.code());
        mapped.put("public_message", error.publicMessage());
        mapped.put("recovery_owner", error.recoveryOwner().name());
        mapped.put("allowed_actions", error.allowedActions().stream().map(Enum::name).toList());
        mapped.put("retryable", error.retryable());
        if (error.retryAfterMs() != null) mapped.put("retry_after_ms", error.retryAfterMs());
        return mapped;
    }
}
