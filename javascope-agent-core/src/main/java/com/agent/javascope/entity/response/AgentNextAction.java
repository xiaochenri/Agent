package com.agent.javascope.entity.response;

/**
 * 用户可见的下一步操作建议。
 */
public class AgentNextAction {

    /** 按钮或建议项显示文案。 */
    private String label = "";
    /** 可直接回填为用户输入的文本。 */
    private String value = "";

    public AgentNextAction() {}

    public AgentNextAction(String label, String value) {
        this.label = label == null ? "" : label;
        this.value = value == null ? "" : value;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value == null ? "" : value;
    }
}
