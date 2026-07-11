package com.agent.javascope.tool.authorization;

/** 工具调用授权结果。 */
public record ToolAuthorizationDecision(Status status, String reason) {

    public enum Status { ALLOW, DENY, REQUIRE_CONFIRMATION }

    public static ToolAuthorizationDecision allow() {
        return new ToolAuthorizationDecision(Status.ALLOW, "");
    }

    public static ToolAuthorizationDecision deny(String reason) {
        return new ToolAuthorizationDecision(Status.DENY, reason == null ? "tool invocation denied" : reason);
    }

    public static ToolAuthorizationDecision requireConfirmation(String reason) {
        return new ToolAuthorizationDecision(Status.REQUIRE_CONFIRMATION, reason == null ? "confirmation required" : reason);
    }
}
