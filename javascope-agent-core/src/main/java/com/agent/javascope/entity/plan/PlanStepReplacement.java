package com.agent.javascope.entity.plan;

import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/** 一次重规划对某个失败或阻塞步骤的局部替换补丁。 */
public class PlanStepReplacement {

    @JsonProperty("replace_step_id")
    private String replaceStepId = "";
    private List<PlanStepDefinition> steps = new ArrayList<>();

    public String getReplaceStepId() { return replaceStepId; }
    public void setReplaceStepId(String replaceStepId) { this.replaceStepId = replaceStepId == null ? "" : replaceStepId; }
    public List<PlanStepDefinition> getSteps() { return steps == null ? new ArrayList<>() : steps; }
    public void setSteps(List<PlanStepDefinition> steps) { this.steps = steps == null ? new ArrayList<>() : steps; }
}
