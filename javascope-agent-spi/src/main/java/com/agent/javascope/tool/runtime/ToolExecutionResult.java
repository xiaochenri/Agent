package com.agent.javascope.tool.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/** 工具执行的统一强类型结果。 */
public record ToolExecutionResult(
        String toolName,
        ToolExecutionStatus status,
        boolean validationPassed,
        List<String> validationRules,
        List<String> validationErrors,
        boolean retryable,
        String errorCode,
        JsonNode data,
        JsonNode metadata) {

    public ToolExecutionResult {
        toolName = toolName == null ? "" : toolName;
        status = status == null ? ToolExecutionStatus.FAILED : status;
        validationRules = validationRules == null ? List.of() : List.copyOf(validationRules);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        errorCode = errorCode == null ? "" : errorCode;
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    public boolean isSuccess() {
        return status == ToolExecutionStatus.SUCCESS;
    }
}
