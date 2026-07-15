package com.agent.javascope.entity.routing;

public class RouteDecision {

    private String route = "task";
    /** task 执行策略：direct 单目标无计划执行，react 观察驱动的动态调查，planned 固定可执行计划。 */
    private String executionMode = "planned";
    private double confidence = 0.5;
    private String reason = "";

    public RouteDecision() {}

    public RouteDecision(String route, double confidence, String reason) {
        this(route, "planned", confidence, reason);
    }

    public RouteDecision(String route, String executionMode, double confidence, String reason) {
        this.route = route == null ? "task" : route;
        this.executionMode = executionMode == null ? "planned" : executionMode;
        this.confidence = confidence;
        this.reason = reason == null ? "" : reason;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route == null ? "task" : route;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode == null ? "planned" : executionMode;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason == null ? "" : reason;
    }
}
