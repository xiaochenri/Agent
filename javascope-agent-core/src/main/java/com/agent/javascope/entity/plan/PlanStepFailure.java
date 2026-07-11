package com.agent.javascope.entity.plan;

import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/** 传递给 revise_plan 的单个失败或阻塞步骤上下文。 */
public class PlanStepFailure {
    @JsonProperty("step_id") private String stepId = "";
    @JsonProperty("step_index") private int stepIndex = -1;
    private PlanStepDefinition step = new PlanStepDefinition();
    private String reason = "";
    @JsonProperty("actual_output") private Map<String, Object> actualOutput = new LinkedHashMap<>();
    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId == null ? "" : stepId; }
    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }
    public PlanStepDefinition getStep() { return step == null ? new PlanStepDefinition() : step; }
    public void setStep(PlanStepDefinition step) { this.step = step == null ? new PlanStepDefinition() : step; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason == null ? "" : reason; }
    public Map<String, Object> getActualOutput() { return actualOutput == null ? new LinkedHashMap<>() : actualOutput; }
    public void setActualOutput(Map<String, Object> actualOutput) { this.actualOutput = actualOutput == null ? new LinkedHashMap<>() : actualOutput; }
}
