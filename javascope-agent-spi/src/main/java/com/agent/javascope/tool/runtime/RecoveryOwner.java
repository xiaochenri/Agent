package com.agent.javascope.tool.runtime;

/** 决定工具最终失败后由谁负责恢复。 */
public enum RecoveryOwner {
    /** 由运行时执行重试、退避或熔断等确定性策略。 */
    SYSTEM,
    /** 由 Agent 修改参数、更换工具或保守结束。 */
    MODEL,
    /** 需要用户澄清、授权或确认。 */
    USER,
    /** 需要开发者修复契约或程序缺陷。 */
    DEVELOPER,
    /** 需要查询状态、执行补偿或人工介入。 */
    COMPENSATION
}
