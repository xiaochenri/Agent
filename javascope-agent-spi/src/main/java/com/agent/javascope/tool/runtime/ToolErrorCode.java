package com.agent.javascope.tool.runtime;

/**
 * Agent 运行时自身产生的稳定工具错误码。
 *
 * <p>此枚举只收录跨业务域的框架错误。股票、支付等业务工具应在各自模块维护
 * 业务错误码，并通过 {@link ToolErrorClassifier} 转换为统一 {@link ToolError}。</p>
 */
public enum ToolErrorCode {
    /** 旧扩展未提供具体错误码时的兼容兜底。 */
    TOOL_EXECUTION_FAILED,
    /** 运行时找不到指定工具。 */
    TOOL_NOT_REGISTERED,
    /** 工具已注册但当前被禁用。 */
    TOOL_DISABLED,
    /** 工具输入不符合 Schema 或语义契约。 */
    TOOL_INPUT_CONTRACT_VIOLATION,
    /** 未预期异常被识别为非法输入。 */
    TOOL_INPUT_INVALID,
    /** 工具执行前需要用户确认。 */
    TOOL_CONFIRMATION_REQUIRED,
    /** 当前调用者无权执行工具。 */
    TOOL_NOT_AUTHORIZED,
    /** 工具调用被限流器拒绝。 */
    TOOL_RATE_LIMITED,
    /** 工具调用被取消或线程被中断。 */
    TOOL_CANCELLED,
    /** 工具调用超过时间预算。 */
    TOOL_TIMEOUT,
    /** 工具依赖的网络连接失败。 */
    TOOL_NETWORK_ERROR,
    /** 工具的通用外部依赖暂时不可用。 */
    TOOL_DEPENDENCY_UNAVAILABLE,
    /** 工具或依赖熔断器已打开。 */
    TOOL_CIRCUIT_OPEN,
    /** 工具并发隔离容量已耗尽。 */
    TOOL_BULKHEAD_REJECTED,
    /** 工具方法返回了空结果。 */
    TOOL_RESULT_NULL,
    /** 工具结果不是可解析的 JSON 对象。 */
    TOOL_RESULT_INVALID_JSON,
    /** 工具返回结果未通过输出契约。 */
    TOOL_OUTPUT_CONTRACT_VIOLATION,
    /** 运行时或工具代码出现未知程序错误。 */
    TOOL_INTERNAL_ERROR,
    /** 有副作用的工具无法确定外部操作是否已成功。 */
    TOOL_SIDE_EFFECT_UNCERTAIN,
    /** Agent 产生的工具动作被执行策略拒绝。 */
    TOOL_ACTION_REJECTED,
    /** 计划工具在有限修正后仍未通过校验。 */
    PLAN_VALIDATION_FAILED,
    /** 计划步骤在执行前未满足依赖或前置条件。 */
    PLAN_PRECONDITION_FAILED,
    /** 澄清请求不符合运行时澄清策略。 */
    CLARIFICATION_POLICY_REJECTED;

    /** @return 对外序列化的稳定错误码 */
    public String code() {
        return name();
    }
}
