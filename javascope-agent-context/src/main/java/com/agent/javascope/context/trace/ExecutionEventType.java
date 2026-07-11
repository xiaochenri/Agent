package com.agent.javascope.context.trace;

/** 可观测执行轨迹中的标准事件类型。 */
public enum ExecutionEventType {
    /** 接收到用户原始输入。 */
    USER_INPUT_RECEIVED,
    /** 准备调用路由模型。 */
    ROUTE_MODEL_REQUESTED,
    /** 路由模型已返回结果。 */
    ROUTE_MODEL_RESPONDED,
    /** 准备调用行动或回复模型。 */
    ACTION_MODEL_REQUESTED,
    /** 行动或回复模型已返回结果。 */
    ACTION_MODEL_RESPONDED,
    /** 准备调用一个工具。 */
    TOOL_REQUESTED,
    /** 工具已返回成功或失败结果。 */
    TOOL_COMPLETED,
    /** 计划生命周期发生变化。 */
    PLAN_EVENT,
    /** 已生成本次执行的最终答案。 */
    FINAL_ANSWER_GENERATED,
    /** 整个执行正常收口。 */
    EXECUTION_COMPLETED,
    /** 整个执行因未处理异常或不可恢复错误终止。 */
    EXECUTION_FAILED
}
