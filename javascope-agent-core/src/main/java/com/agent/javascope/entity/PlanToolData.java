package com.agent.javascope.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanToolData {

    /** 模型对用户任务的结构化理解，字段由提示词约束但内容允许扩展。 */
    @JsonProperty("task_understanding")
    private Map<String, Object> taskUnderstanding = new LinkedHashMap<>();
    /** 可执行计划步骤列表。 */
    private List<PlanStepDefinition> plan = new ArrayList<>();

    public PlanToolData() {}

    public PlanToolData(Map<String, Object> taskUnderstanding, List<PlanStepDefinition> plan) {
        this.taskUnderstanding = taskUnderstanding == null ? new LinkedHashMap<>() : taskUnderstanding;
        this.plan = plan == null ? new ArrayList<>() : plan;
    }

    public Map<String, Object> getTaskUnderstanding() {
        return taskUnderstanding == null ? new LinkedHashMap<>() : taskUnderstanding;
    }

    public void setTaskUnderstanding(Map<String, Object> taskUnderstanding) {
        this.taskUnderstanding = taskUnderstanding == null ? new LinkedHashMap<>() : taskUnderstanding;
    }

    public List<PlanStepDefinition> getPlan() {
        return plan == null ? new ArrayList<>() : plan;
    }

    public void setPlan(List<PlanStepDefinition> plan) {
        this.plan = plan == null ? new ArrayList<>() : plan;
    }
}
