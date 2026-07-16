package com.agent.javascope.tool.error;

import com.agent.javascope.tool.runtime.RecoveryAction;
import com.agent.javascope.tool.runtime.RecoveryOwner;
import com.agent.javascope.tool.runtime.ToolError;
import com.agent.javascope.tool.runtime.ToolErrorCategory;
import com.agent.javascope.tool.runtime.ToolErrorClassifier;
import com.agent.javascope.tool.runtime.ToolErrorCode;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** 通用工具错误分类器：分类稳定，且不向 Agent 暴露原始异常消息。 */
public final class DefaultToolErrorClassifier implements ToolErrorClassifier {

    /** 无状态默认分类器单例。 */
    public static final DefaultToolErrorClassifier INSTANCE = new DefaultToolErrorClassifier();

    private DefaultToolErrorClassifier() {}

    @Override
    public ToolError classify(String code, String publicMessage, boolean retryable) {
        String normalizedCode = normalizeCode(code);
        ToolErrorCategory category = categoryFor(normalizedCode);
        boolean effectiveRetryable = retryable && isTransient(category);
        return build(category, normalizedCode, safeMessage(publicMessage, category), effectiveRetryable, "");
    }

    @Override
    public ToolError classify(Throwable error) {
        Throwable cause = unwrap(error);
        String exceptionType = cause == null ? "" : cause.getClass().getName();
        if (cause instanceof CancellationException || cause instanceof InterruptedException) {
            return build(ToolErrorCategory.CANCELLED, ToolErrorCode.TOOL_CANCELLED,
                    "工具调用已取消", false, exceptionType);
        }
        if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
            return build(ToolErrorCategory.TIMEOUT, ToolErrorCode.TOOL_TIMEOUT,
                    "工具调用超时", true, exceptionType);
        }
        if (cause instanceof ConnectException || cause instanceof IOException) {
            return build(ToolErrorCategory.NETWORK_ERROR, ToolErrorCode.TOOL_NETWORK_ERROR,
                    "工具依赖网络暂时不可用", true, exceptionType);
        }
        if (cause instanceof SecurityException) {
            return build(ToolErrorCategory.NOT_AUTHORIZED, ToolErrorCode.TOOL_NOT_AUTHORIZED,
                    "当前请求无权执行该工具", false, exceptionType);
        }
        if (cause instanceof IllegalArgumentException) {
            return build(ToolErrorCategory.INPUT_INVALID, ToolErrorCode.TOOL_INPUT_INVALID,
                    "工具输入不合法", false, exceptionType);
        }
        return build(ToolErrorCategory.INTERNAL_ERROR, ToolErrorCode.TOOL_INTERNAL_ERROR,
                "工具内部执行失败", false, exceptionType);
    }

    private ToolErrorCategory categoryFor(String code) {
        if (code.contains("CONFIRMATION_REQUIRED")) return ToolErrorCategory.AUTH_CONFIRMATION_REQUIRED;
        if (code.contains("NOT_AUTHORIZED") || code.contains("UNAUTHORIZED")) return ToolErrorCategory.NOT_AUTHORIZED;
        if (code.contains("RATE_LIMIT")) return ToolErrorCategory.RATE_LIMITED;
        if (code.contains("TIMEOUT")) return ToolErrorCategory.TIMEOUT;
        if (code.contains("NETWORK") || code.contains("CONNECTION")) return ToolErrorCategory.NETWORK_ERROR;
        if (code.contains("CIRCUIT_OPEN")) return ToolErrorCategory.CIRCUIT_OPEN;
        if (code.contains("BULKHEAD")) return ToolErrorCategory.BULKHEAD_REJECTED;
        if (code.contains("CANCEL")) return ToolErrorCategory.CANCELLED;
        if (code.contains("SIDE_EFFECT_UNCERTAIN")) return ToolErrorCategory.SIDE_EFFECT_UNCERTAIN;
        if (code.contains("OUTPUT_CONTRACT") || code.contains("RESULT_NULL")
                || code.contains("RESULT_INVALID") || code.contains("INVALID_JSON")) {
            return ToolErrorCategory.OUTPUT_CONTRACT_VIOLATION;
        }
        if (code.endsWith("_NOT_FOUND") || code.contains("NOT_FOUND")) return ToolErrorCategory.NOT_FOUND;
        if (code.contains("NO_DATA")) return ToolErrorCategory.NO_DATA;
        if (code.endsWith("_UNAVAILABLE") || code.contains("DEPENDENCY_UNAVAILABLE")) {
            return ToolErrorCategory.DEPENDENCY_UNAVAILABLE;
        }
        if (code.contains("PLAN_") || code.contains("PRECONDITION") || code.contains("ACTION_REJECTED")
                || code.contains("POLICY_REJECTED")) {
            return ToolErrorCategory.BUSINESS_RULE_VIOLATION;
        }
        if (code.contains("INPUT") || code.endsWith("_REQUIRED") || code.startsWith("INVALID_")) {
            return ToolErrorCategory.INPUT_INVALID;
        }
        if (code.contains("BUSINESS_RULE") || code.startsWith("EPS_") || code.startsWith("PE_")) {
            return ToolErrorCategory.BUSINESS_RULE_VIOLATION;
        }
        return ToolErrorCategory.INTERNAL_ERROR;
    }

    private ToolError build(
            ToolErrorCategory category,
            String code,
            String publicMessage,
            boolean retryable,
            String exceptionType) {
        RecoveryOwner owner = ownerFor(category);
        return new ToolError(category, code, publicMessage, owner, actionsFor(category), retryable,
                null, exceptionType, "");
    }

    /** 内置错误码构建入口，避免框架代码重复书写字符串。 */
    private ToolError build(
            ToolErrorCategory category,
            ToolErrorCode code,
            String publicMessage,
            boolean retryable,
            String exceptionType) {
        return build(category, code.code(), publicMessage, retryable, exceptionType);
    }

    private RecoveryOwner ownerFor(ToolErrorCategory category) {
        return switch (category) {
            case RATE_LIMITED, TIMEOUT, NETWORK_ERROR, DEPENDENCY_UNAVAILABLE,
                    CIRCUIT_OPEN, BULKHEAD_REJECTED -> RecoveryOwner.SYSTEM;
            case INPUT_INVALID, BUSINESS_RULE_VIOLATION, NO_DATA, NOT_FOUND -> RecoveryOwner.MODEL;
            case AUTH_CONFIRMATION_REQUIRED, NOT_AUTHORIZED -> RecoveryOwner.USER;
            case SIDE_EFFECT_UNCERTAIN -> RecoveryOwner.COMPENSATION;
            case OUTPUT_CONTRACT_VIOLATION, INTERNAL_ERROR, CANCELLED -> RecoveryOwner.DEVELOPER;
        };
    }

    private List<RecoveryAction> actionsFor(ToolErrorCategory category) {
        return switch (category) {
            case RATE_LIMITED, TIMEOUT, NETWORK_ERROR, DEPENDENCY_UNAVAILABLE, BULKHEAD_REJECTED ->
                    List.of(RecoveryAction.RETRY_SAME_CALL, RecoveryAction.USE_ALTERNATIVE_TOOL,
                            RecoveryAction.FINALIZE_WITH_LIMITATION);
            case INPUT_INVALID, BUSINESS_RULE_VIOLATION ->
                    List.of(RecoveryAction.MODIFY_INPUT, RecoveryAction.ASK_USER,
                            RecoveryAction.FINALIZE_WITH_LIMITATION);
            case NO_DATA, NOT_FOUND, CIRCUIT_OPEN ->
                    List.of(RecoveryAction.USE_ALTERNATIVE_TOOL, RecoveryAction.FINALIZE_WITH_LIMITATION);
            case AUTH_CONFIRMATION_REQUIRED -> List.of(RecoveryAction.ASK_USER, RecoveryAction.ABORT);
            case NOT_AUTHORIZED ->
                    List.of(RecoveryAction.ASK_USER, RecoveryAction.USE_ALTERNATIVE_TOOL, RecoveryAction.ABORT);
            case SIDE_EFFECT_UNCERTAIN -> List.of(RecoveryAction.COMPENSATE, RecoveryAction.ASK_USER, RecoveryAction.ABORT);
            case OUTPUT_CONTRACT_VIOLATION, INTERNAL_ERROR, CANCELLED ->
                    List.of(RecoveryAction.FINALIZE_WITH_LIMITATION, RecoveryAction.ABORT);
        };
    }

    private boolean isTransient(ToolErrorCategory category) {
        return switch (category) {
            case RATE_LIMITED, TIMEOUT, NETWORK_ERROR, DEPENDENCY_UNAVAILABLE, BULKHEAD_REJECTED -> true;
            default -> false;
        };
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) return ToolErrorCode.TOOL_EXECUTION_FAILED.code();
        return code.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String safeMessage(String message, ToolErrorCategory category) {
        if (message == null || message.isBlank()) {
            return switch (category) {
                case INPUT_INVALID -> "工具输入不合法";
                case AUTH_CONFIRMATION_REQUIRED -> "执行该工具前需要用户确认";
                case NOT_AUTHORIZED -> "当前请求无权执行该工具";
                case NOT_FOUND, NO_DATA -> "未找到可用数据";
                case RATE_LIMITED -> "工具调用过于频繁";
                case TIMEOUT -> "工具调用超时";
                case NETWORK_ERROR, DEPENDENCY_UNAVAILABLE -> "工具依赖暂时不可用";
                case OUTPUT_CONTRACT_VIOLATION -> "工具返回结果不符合契约";
                default -> "工具执行失败";
            };
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException
                || current instanceof CompletionException
                || current instanceof ExecutionException) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) break;
            current = cause;
        }
        return current;
    }
}
