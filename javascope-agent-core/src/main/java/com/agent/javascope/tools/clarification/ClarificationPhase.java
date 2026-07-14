package com.agent.javascope.tools.clarification;

/** 澄清发生阶段：首次行动判断或执行过程中根据观察产生的动态澄清。 */
public enum ClarificationPhase {
    INITIAL("initial"),
    RUNTIME("runtime");

    private final String value;

    ClarificationPhase(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** 未知值按首次澄清处理，避免把普通输入误判为运行时阻塞。 */
    public static ClarificationPhase from(Object raw) {
        return raw != null && "runtime".equalsIgnoreCase(String.valueOf(raw).trim())
                ? RUNTIME
                : INITIAL;
    }
}
