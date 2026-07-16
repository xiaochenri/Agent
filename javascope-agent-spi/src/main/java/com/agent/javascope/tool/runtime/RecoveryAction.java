package com.agent.javascope.tool.runtime;

/** 工具失败后允许执行的恢复动作。 */
public enum RecoveryAction {
    /** 在运行时内部重试原始工具和输入。 */
    RETRY_SAME_CALL,
    /** 修改输入后重新调用同一工具。 */
    MODIFY_INPUT,
    /** 使用可替代的其他工具。 */
    USE_ALTERNATIVE_TOOL,
    /** 请求用户补充信息、授权或确认。 */
    ASK_USER,
    /** 披露局限后使用已验证证据结束。 */
    FINALIZE_WITH_LIMITATION,
    /** 执行状态查询或补偿操作。 */
    COMPENSATE,
    /** 终止当前工具恢复路径。 */
    ABORT
}
