package com.agent.javascope.validation;

public enum StepFailureCode {
    NONE("NONE", "无异常"),
    TOOL_EXECUTION_FAILED("TOOL_EXECUTION_FAILED", "工具执行失败"),
    SEMANTIC_MISMATCH("SEMANTIC_MISMATCH", "执行结果与预期语义不一致");

    private final String code;
    private final String descriptionZh;

    StepFailureCode(String code, String descriptionZh) {
        this.code = code;
        this.descriptionZh = descriptionZh;
    }

    public String code() {
        return code;
    }

    public String descriptionZh() {
        return descriptionZh;
    }
}
