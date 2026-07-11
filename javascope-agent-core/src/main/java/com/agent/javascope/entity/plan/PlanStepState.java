package com.agent.javascope.entity.plan;

import com.agent.javascope.plan.PlanStepStatus;
import java.util.LinkedHashMap;
import java.util.Map;

public class PlanStepState {

    private final String stepId;
    private final String name;
    private final String description;
    private final String toolName;
    private final Map<String, Object> input;
    private final String expectedOutcome;
    private final boolean dependsOnPrevious;
    private final String previousStepId;
    private final String nextStepId;
    private PlanStepStatus status = PlanStepStatus.PENDING;
    private Map<String, Object> actualOutput = new LinkedHashMap<>();

    public PlanStepState(
            String stepId,
            String name,
            String description,
            String toolName,
            Map<String, Object> input,
            String expectedOutcome,
            boolean dependsOnPrevious,
            String previousStepId,
            String nextStepId) {
        this.stepId = stepId;
        this.name = name;
        this.description = description;
        this.toolName = toolName;
        this.input = input;
        this.expectedOutcome = expectedOutcome;
        this.dependsOnPrevious = dependsOnPrevious;
        this.previousStepId = previousStepId;
        this.nextStepId = nextStepId;
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

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public String getExpectedOutcome() {
        return expectedOutcome;
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

    public PlanStepStatus getStatus() {
        return status;
    }

    public void setStatus(PlanStepStatus status) {
        this.status = status;
    }

    public Map<String, Object> getActualOutput() {
        return actualOutput;
    }

    public void setActualOutput(Map<String, Object> actualOutput) {
        this.actualOutput = actualOutput;
    }
}
