package com.agent.javascope.entity.routing;

public class RouteDecision {

    private String route = "task";
    private double confidence = 0.5;
    private String reason = "";

    public RouteDecision() {}

    public RouteDecision(String route, double confidence, String reason) {
        this.route = route == null ? "task" : route;
        this.confidence = confidence;
        this.reason = reason == null ? "" : reason;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route == null ? "task" : route;
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
