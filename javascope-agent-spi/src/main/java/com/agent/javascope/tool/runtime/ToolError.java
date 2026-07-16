package com.agent.javascope.tool.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 工具失败的统一结构化协议。
 *
 * @param category 跨工具稳定错误分类，供运行时选择策略
 * @param code 框架或业务域稳定错误码
 * @param publicMessage 可安全进入 Prompt 和用户回复的消息
 * @param recoveryOwner 对下一步恢复负责的主体
 * @param allowedActions 当前错误允许的恢复动作
 * @param retryable 该错误在能力上是否适合重试，不代表一定实际重试
 * @param retryAfterMs 依赖方建议的重试等待时间
 * @param exceptionType 仅供内部日志和 Trace 使用的异常类型
 * @param detailsRef 关联内部详细日志的引用
 */
public record ToolError(
        ToolErrorCategory category,
        String code,
        @JsonProperty("public_message") String publicMessage,
        @JsonProperty("recovery_owner") RecoveryOwner recoveryOwner,
        @JsonProperty("allowed_actions") List<RecoveryAction> allowedActions,
        boolean retryable,
        @JsonProperty("retry_after_ms") Long retryAfterMs,
        @JsonProperty("exception_type") String exceptionType,
        @JsonProperty("details_ref") String detailsRef) {

    public ToolError {
        category = category == null ? ToolErrorCategory.INTERNAL_ERROR : category;
        code = code == null || code.isBlank() ? ToolErrorCode.TOOL_EXECUTION_FAILED.code() : code;
        publicMessage = publicMessage == null || publicMessage.isBlank() ? "工具执行失败" : publicMessage;
        recoveryOwner = recoveryOwner == null ? RecoveryOwner.DEVELOPER : recoveryOwner;
        allowedActions = allowedActions == null ? List.of(RecoveryAction.ABORT) : List.copyOf(allowedActions);
        exceptionType = exceptionType == null ? "" : exceptionType;
        detailsRef = detailsRef == null ? "" : detailsRef;
    }
}
