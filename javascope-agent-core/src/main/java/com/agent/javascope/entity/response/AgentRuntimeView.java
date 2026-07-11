package com.agent.javascope.entity.response;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.entity.plan.PlanRevisionRecord;
import com.agent.javascope.entity.plan.PlanStepView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 内部执行视图，默认不直接展示给普通用户。
 */
public class AgentRuntimeView {

    /** 模型或规划器对任务的结构化理解。 */
    @JsonProperty("task_understanding")
    private Object taskUnderstanding;
    /** 执行计划视图。 */
    private List<PlanStepView> plan = new ArrayList<>();
    /** 工具调用和推理执行日志。 */
    @JsonProperty("execution_log")
    private List<AgentExecutionLogEntry> executionLog = new ArrayList<>();
    /** 计划修订记录。 */
    @JsonProperty("revised_plan")
    private List<PlanRevisionRecord> revisedPlan = new ArrayList<>();
    /** 计划生命周期事件。 */
    @JsonProperty("plan_lifecycle")
    private List<Map<String, Object>> planLifecycle = new ArrayList<>();
    /** 阻塞原因。 */
    @JsonProperty("blocked_reason")
    private String blockedReason = "";
    /** 框架识别出的风险标记。 */
    @JsonProperty("risk_flags")
    private List<String> riskFlags = new ArrayList<>();

    public Object getTaskUnderstanding() {
        return taskUnderstanding;
    }

    public void setTaskUnderstanding(Object taskUnderstanding) {
        this.taskUnderstanding = taskUnderstanding;
    }

    public List<PlanStepView> getPlan() {
        return plan;
    }

    public void setPlan(List<PlanStepView> plan) {
        this.plan = plan == null ? new ArrayList<>() : plan;
    }

    public List<AgentExecutionLogEntry> getExecutionLog() {
        return executionLog;
    }

    public void setExecutionLog(List<AgentExecutionLogEntry> executionLog) {
        this.executionLog = executionLog == null ? new ArrayList<>() : executionLog;
    }

    public List<PlanRevisionRecord> getRevisedPlan() {
        return revisedPlan;
    }

    public void setRevisedPlan(List<PlanRevisionRecord> revisedPlan) {
        this.revisedPlan = revisedPlan == null ? new ArrayList<>() : revisedPlan;
    }

    public List<Map<String, Object>> getPlanLifecycle() {
        return planLifecycle;
    }

    public void setPlanLifecycle(List<Map<String, Object>> planLifecycle) {
        this.planLifecycle = planLifecycle == null ? new ArrayList<>() : planLifecycle;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason == null ? "" : blockedReason;
    }

    public List<String> getRiskFlags() {
        return riskFlags;
    }

    public void setRiskFlags(List<String> riskFlags) {
        this.riskFlags = riskFlags == null ? new ArrayList<>() : riskFlags;
    }
}
