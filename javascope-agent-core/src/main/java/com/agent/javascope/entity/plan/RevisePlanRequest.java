package com.agent.javascope.entity.plan;

import com.agent.javascope.contract.plan.FailedStepHistoryItem;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RevisePlanRequest {

    /** 用户原始任务文本。 */
    @JsonProperty("user_input")
    private String userInput = "";
    /** 触发重规划的原因。 */
    private String reason = "";
    /** 当前生效计划。 */
    @JsonProperty("current_plan")
    private List<PlanStepDefinition> currentPlan = new ArrayList<>();
    /** 当前失败步骤下标。 */
    @JsonProperty("failed_step_index")
    private int failedStepIndex = -1;
    /** 当前失败步骤详情。 */
    @JsonProperty("failed_step")
    private PlanStepDefinition failedStep = new PlanStepDefinition();
    /** 本轮全部失败或阻塞步骤，供批量补丁式重规划使用。 */
    @JsonProperty("failed_steps")
    private List<PlanStepFailure> failedSteps = new ArrayList<>();
    /** 最近一次步骤校验或工具失败上下文。 */
    @JsonProperty("failure_context")
    private Map<String, Object> failureContext = new LinkedHashMap<>();
    /** 上一轮失败计划指纹，用于避免重复生成同构计划。 */
    @JsonProperty("previous_plan_fingerprint")
    private String previousPlanFingerprint = "";
    /** 已成功执行步骤的指纹列表，用于禁止重规划重复成功步骤。 */
    @JsonProperty("completed_step_fingerprints")
    private List<String> completedStepFingerprints = new ArrayList<>();
    /** 历史失败步骤的指纹列表，用于禁止重试相同工具与入参组合。 */
    @JsonProperty("failed_step_fingerprints")
    private List<String> failedStepFingerprints = new ArrayList<>();
    /** 历史失败步骤详情，用于给模型提供失败原因与实际输出。 */
    @JsonProperty("failed_step_history")
    private List<FailedStepHistoryItem> failedStepHistory = new ArrayList<>();

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput == null ? "" : userInput;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason == null ? "" : reason;
    }

    public List<PlanStepDefinition> getCurrentPlan() {
        return currentPlan == null ? new ArrayList<>() : currentPlan;
    }

    public void setCurrentPlan(List<PlanStepDefinition> currentPlan) {
        this.currentPlan = currentPlan == null ? new ArrayList<>() : currentPlan;
    }

    public int getFailedStepIndex() {
        return failedStepIndex;
    }

    public void setFailedStepIndex(int failedStepIndex) {
        this.failedStepIndex = failedStepIndex;
    }

    public PlanStepDefinition getFailedStep() {
        return failedStep == null ? new PlanStepDefinition() : failedStep;
    }

    public void setFailedStep(PlanStepDefinition failedStep) {
        this.failedStep = failedStep == null ? new PlanStepDefinition() : failedStep;
    }

    public List<PlanStepFailure> getFailedSteps() {
        return failedSteps == null ? new ArrayList<>() : failedSteps;
    }

    public void setFailedSteps(List<PlanStepFailure> failedSteps) {
        this.failedSteps = failedSteps == null ? new ArrayList<>() : failedSteps;
    }

    public Map<String, Object> getFailureContext() {
        return failureContext == null ? new LinkedHashMap<>() : failureContext;
    }

    public void setFailureContext(Map<String, Object> failureContext) {
        this.failureContext = failureContext == null ? new LinkedHashMap<>() : failureContext;
    }

    public String getPreviousPlanFingerprint() {
        return previousPlanFingerprint;
    }

    public void setPreviousPlanFingerprint(String previousPlanFingerprint) {
        this.previousPlanFingerprint = previousPlanFingerprint == null ? "" : previousPlanFingerprint;
    }

    public List<String> getCompletedStepFingerprints() {
        return completedStepFingerprints == null ? new ArrayList<>() : completedStepFingerprints;
    }

    public void setCompletedStepFingerprints(List<String> completedStepFingerprints) {
        this.completedStepFingerprints = completedStepFingerprints == null ? new ArrayList<>() : completedStepFingerprints;
    }

    public List<String> getFailedStepFingerprints() {
        return failedStepFingerprints == null ? new ArrayList<>() : failedStepFingerprints;
    }

    public void setFailedStepFingerprints(List<String> failedStepFingerprints) {
        this.failedStepFingerprints = failedStepFingerprints == null ? new ArrayList<>() : failedStepFingerprints;
    }

    public List<FailedStepHistoryItem> getFailedStepHistory() {
        return failedStepHistory == null ? new ArrayList<>() : failedStepHistory;
    }

    public void setFailedStepHistory(List<FailedStepHistoryItem> failedStepHistory) {
        this.failedStepHistory = failedStepHistory == null ? new ArrayList<>() : failedStepHistory;
    }
}
