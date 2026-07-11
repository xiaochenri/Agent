package com.agent.javascope.tool.annotation;

/** 工具风险等级，用于自动调用、用户确认和高危操作拦截策略。 */
public enum ToolDangerLevel {
    /** 只读、无外部副作用的安全工具。 */
    SAFE,
    /** 低风险写入或可逆操作。 */
    LOW,
    /** 修改用户数据、创建任务、发送通知等中等风险操作。 */
    MEDIUM,
    /** 下单、转账、删除等默认需要强确认的高风险操作。 */
    HIGH,
    /** 默认不允许模型自动执行的关键操作。 */
    CRITICAL
}
