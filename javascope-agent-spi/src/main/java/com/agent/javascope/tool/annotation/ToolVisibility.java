package com.agent.javascope.tool.annotation;

/** 工具可见性，决定工具是否进入模型 prompt 以及是否可被 runtime 调用。 */
public enum ToolVisibility {
    /** 暴露给模型，可由模型主动选择调用。 */
    MODEL_VISIBLE,
    /** 暴露给模型但标记为内部流程工具，用于规划、重规划等控制动作。 */
    MODEL_INTERNAL,
    /** 不暴露给模型，仅 runtime 代码可直接调用。 */
    RUNTIME_INTERNAL,
    /** 禁用工具，不暴露也不允许执行。 */
    DISABLED
}
