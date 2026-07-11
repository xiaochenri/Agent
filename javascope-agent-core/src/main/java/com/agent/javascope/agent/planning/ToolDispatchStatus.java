package com.agent.javascope.agent.planning;

/**
 * 工具分发完成后对主循环的控制指令。
 */
public enum ToolDispatchStatus {
    /** 继续进入下一轮 reasoning。 */
    CONTINUE_REASONING,
    /** 当前执行已形成终态，主循环可直接返回。 */
    FINISHED
}
