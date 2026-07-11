package com.agent.javascope.plan;

public enum PlanLifecycleEvent {
    PLAN_CREATED("plan_created"),
    STEP_STARTED("step_started"),
    STEP_COMPLETED("step_completed"),
    STEP_FAILED("step_failed"),
    STEP_FAILED_EXPECTED_MISMATCH("step_failed_expected_mismatch"),
    PLAN_COMPLETED("plan_completed");

    private final String value;

    PlanLifecycleEvent(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
