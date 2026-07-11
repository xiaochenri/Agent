package com.agent.javascope.entity.plan;

import com.agent.javascope.plan.PlanStepStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

public class PlanStepView {

    /** 计划步骤在当前计划版本内的唯一标识。 */
    @JsonProperty("step_id")
    private String stepId = "";
    /** 步骤名称。 */
    private String name = "";
    /** 步骤说明。 */
    private String description = "";
    /** 步骤调用工具名称。 */
    private String tool = "";
    /** 步骤工具入参。 */
    private Map<String, Object> input = new LinkedHashMap<>();
    /** 步骤预期产出。 */
    @JsonProperty("expected_outcome")
    private String expectedOutcome = "";
    /** 步骤执行状态。 */
    private PlanStepStatus status = PlanStepStatus.PENDING;
    /** 是否必须依赖上一步成功完成。 */
    @JsonProperty("depends_on_previous")
    private boolean dependsOnPrevious;
    /** 前置步骤 ID，没有前置步骤时为空字符串。 */
    @JsonProperty("previous_step_id")
    private String previousStepId = "";
    /** 后续步骤 ID，没有后续步骤时为空字符串。 */
    @JsonProperty("next_step_id")
    private String nextStepId = "";
    /** 步骤实际输出。 */
    @JsonProperty("actual_output")
    private Map<String, Object> actualOutput = new LinkedHashMap<>();

    public PlanStepView() {}

    public PlanStepView(PlanStepState step) {
        this.stepId = step.getStepId();
        this.name = step.getName();
        this.description = step.getDescription();
        this.tool = step.getToolName();
        this.input = step.getInput();
        this.expectedOutcome = step.getExpectedOutcome();
        this.status = step.getStatus();
        this.dependsOnPrevious = step.isDependsOnPrevious();
        this.previousStepId = step.getPreviousStepId() == null ? "" : step.getPreviousStepId();
        this.nextStepId = step.getNextStepId() == null ? "" : step.getNextStepId();
        this.actualOutput = step.getActualOutput();
    }

    public String getStepId() {
        return stepId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getTool() {
        return tool;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public String getExpectedOutcome() {
        return expectedOutcome;
    }

    public PlanStepStatus getStatus() {
        return status;
    }

    public boolean isDependsOnPrevious() {
        return dependsOnPrevious;
    }

    public String getPreviousStepId() {
        return previousStepId;
    }

    public String getNextStepId() {
        return nextStepId;
    }

    public Map<String, Object> getActualOutput() {
        return actualOutput;
    }
}
