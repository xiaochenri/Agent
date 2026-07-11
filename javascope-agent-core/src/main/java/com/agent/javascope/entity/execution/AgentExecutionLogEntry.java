package com.agent.javascope.entity.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AgentExecutionLogEntry {

    /** 日志步骤标识，例如 reasoning_round_1 或 tool_call_round_1。 */
    private String step = "";
    /** 日志对应的工具或内部阶段名称。 */
    @JsonProperty("tool_name")
    private String toolName = "";
    /** 该日志记录的输入内容。 */
    private Object input;
    /** 该日志记录的输出内容。 */
    private Object output;
    /** 该日志结果的置信度。 */
    private double confidence;

    public AgentExecutionLogEntry() {}

    public AgentExecutionLogEntry(String step, String toolName, Object input, Object output, double confidence) {
        this.step = step;
        this.toolName = toolName;
        this.input = input;
        this.output = output;
        this.confidence = confidence;
    }

    public String getStep() {
        return step;
    }

    public void setStep(String step) {
        this.step = step == null ? "" : step;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName == null ? "" : toolName;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
    }

    public Object getOutput() {
        return output;
    }

    public void setOutput(Object output) {
        this.output = output;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
