package com.agent.javascope.contract.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

public class FailedStepHistoryItem {

    /** 失败步骤名称。 */
    @JsonProperty("step_name")
    private String stepName = "";
    /** 失败步骤调用的工具名称。 */
    private String tool = "";
    /** 失败步骤的工具入参。 */
    private Map<String, Object> input = new LinkedHashMap<>();
    /** 失败原因摘要。 */
    private String reason = "";
    /** 失败步骤的实际工具输出。 */
    @JsonProperty("actual_output")
    private Map<String, Object> actualOutput = new LinkedHashMap<>();

    public FailedStepHistoryItem() {}

    public FailedStepHistoryItem(
            String stepName, String tool, Map<String, Object> input, String reason, Map<String, Object> actualOutput) {
        this.stepName = stepName;
        this.tool = tool;
        this.input = input == null ? new LinkedHashMap<>() : input;
        this.reason = reason;
        this.actualOutput = actualOutput == null ? new LinkedHashMap<>() : actualOutput;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName == null ? "" : stepName;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool == null ? "" : tool;
    }

    public Map<String, Object> getInput() {
        return input == null ? new LinkedHashMap<>() : input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input == null ? new LinkedHashMap<>() : input;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason == null ? "" : reason;
    }

    public Map<String, Object> getActualOutput() {
        return actualOutput == null ? new LinkedHashMap<>() : actualOutput;
    }

    public void setActualOutput(Map<String, Object> actualOutput) {
        this.actualOutput = actualOutput == null ? new LinkedHashMap<>() : actualOutput;
    }
}
