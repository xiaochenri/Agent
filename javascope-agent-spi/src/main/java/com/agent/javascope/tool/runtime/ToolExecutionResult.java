package com.agent.javascope.tool.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * 工具执行的统一强类型结果。
 *
 * <p>顶层 {@code validationErrors/retryable/errorCode} 保留给旧日志和扩展使用；
 * 新恢复逻辑必须使用 {@link #error()} 中的分类、责任方和允许动作。</p>
 *
 * @param toolName 工具注册名称
 * @param status 工具执行成功或失败状态
 * @param validationPassed 结果是否通过契约和语义校验
 * @param validationRules 已应用的校验规则
 * @param validationErrors 供旧协议使用的错误摘要
 * @param retryable 供旧协议使用的重试能力标记
 * @param errorCode 供旧协议使用的稳定错误码
 * @param data 成功业务数据，或仅供审计的原始失败载荷
 * @param metadata 调用、重试和 Trace 元数据
 * @param error 失败时的统一结构化错误；成功时为 {@code null}
 */
public record ToolExecutionResult(
        String toolName,
        ToolExecutionStatus status,
        boolean validationPassed,
        List<String> validationRules,
        List<String> validationErrors,
        boolean retryable,
        String errorCode,
        JsonNode data,
        JsonNode metadata,
        ToolError error) {

    public ToolExecutionResult {
        toolName = toolName == null ? "" : toolName;
        status = status == null ? ToolExecutionStatus.FAILED : status;
        validationRules = validationRules == null ? List.of() : List.copyOf(validationRules);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        errorCode = errorCode == null ? "" : errorCode;
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (status == ToolExecutionStatus.SUCCESS) {
            error = null;
        } else if (error == null) {
            error = new ToolError(
                    ToolErrorCategory.INTERNAL_ERROR,
                    errorCode,
                    validationErrors.stream().findFirst().orElse("工具执行失败"),
                    RecoveryOwner.DEVELOPER,
                    List.of(RecoveryAction.FINALIZE_WITH_LIMITATION, RecoveryAction.ABORT),
                    retryable,
                    null,
                    "",
                    "");
        }
        if (error != null) {
            errorCode = error.code();
            retryable = error.retryable();
        }
    }

    /** 保留旧构造器，让未迁移的扩展仍可保持源码兼容。 */
    public ToolExecutionResult(
            String toolName,
            ToolExecutionStatus status,
            boolean validationPassed,
            List<String> validationRules,
            List<String> validationErrors,
            boolean retryable,
            String errorCode,
            JsonNode data,
            JsonNode metadata) {
        this(toolName, status, validationPassed, validationRules, validationErrors,
                retryable, errorCode, data, metadata, null);
    }

    public boolean isSuccess() {
        return status == ToolExecutionStatus.SUCCESS;
    }
}
