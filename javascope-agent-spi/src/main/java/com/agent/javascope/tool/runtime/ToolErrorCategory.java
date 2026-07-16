package com.agent.javascope.tool.runtime;

/** 跨工具稳定的低基数错误分类。 */
public enum ToolErrorCategory {
    /** 输入 Schema、格式或必填参数不合法。 */
    INPUT_INVALID,
    /** 执行前缺少用户确认。 */
    AUTH_CONFIRMATION_REQUIRED,
    /** 当前调用者没有工具权限。 */
    NOT_AUTHORIZED,
    /** 输入结构合法，但违反业务规则。 */
    BUSINESS_RULE_VIOLATION,
    /** 查询正常完成，但没有可用数据。 */
    NO_DATA,
    /** 指定业务对象不存在。 */
    NOT_FOUND,
    /** 调用超过工具或依赖限流配额。 */
    RATE_LIMITED,
    /** 调用超过时间预算。 */
    TIMEOUT,
    /** 依赖网络连接或传输失败。 */
    NETWORK_ERROR,
    /** 工具依赖的服务当前不可用。 */
    DEPENDENCY_UNAVAILABLE,
    /** 工具输出不符合 Schema 或语义契约。 */
    OUTPUT_CONTRACT_VIOLATION,
    /** 未知程序缺陷或运行时错误。 */
    INTERNAL_ERROR,
    /** 工具或依赖的熔断器已打开。 */
    CIRCUIT_OPEN,
    /** 工具并发隔离资源已耗尽。 */
    BULKHEAD_REJECTED,
    /** 调用被主动取消或执行线程被中断。 */
    CANCELLED,
    /** 写操作超时等情况下无法确定副作用是否已发生。 */
    SIDE_EFFECT_UNCERTAIN
}
