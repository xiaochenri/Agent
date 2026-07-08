package com.agent.javascope.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

public class PlanStepDefinition {

    /** 步骤名称，用于展示和执行日志定位。 */
    private String name = "";
    /** 步骤说明，用于描述该步骤要完成的具体动作。 */
    private String description = "";
    /** 执行该步骤时调用的工具名称，对应 AgentTool 注册名。 */
    private String tool = "";
    /** 工具入参。不同工具的入参结构不同，因此保留为动态对象。 */
    private Map<String, Object> input = new LinkedHashMap<>();
    /** 预期产出，用于步骤执行后的语义校验。 */
    @JsonProperty("expected_outcome")
    private String expectedOutcome = "";
    /** 是否必须依赖上一步成功完成。 */
    @JsonProperty("depends_on_previous")
    private boolean dependsOnPrevious;

    public PlanStepDefinition() {}

    public PlanStepDefinition(
            String name, String description, String tool, Map<String, Object> input, String expectedOutcome) {
        this(name, description, tool, input, expectedOutcome, false);
    }

    public PlanStepDefinition(
            String name,
            String description,
            String tool,
            Map<String, Object> input,
            String expectedOutcome,
            boolean dependsOnPrevious) {
        this.name = name;
        this.description = description;
        this.tool = tool;
        this.input = input == null ? new LinkedHashMap<>() : input;
        this.expectedOutcome = expectedOutcome;
        this.dependsOnPrevious = dependsOnPrevious;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
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

    public String getExpectedOutcome() {
        return expectedOutcome;
    }

    public void setExpectedOutcome(String expectedOutcome) {
        this.expectedOutcome = expectedOutcome == null ? "" : expectedOutcome;
    }

    public boolean isDependsOnPrevious() {
        return dependsOnPrevious;
    }

    public void setDependsOnPrevious(boolean dependsOnPrevious) {
        this.dependsOnPrevious = dependsOnPrevious;
    }
}
