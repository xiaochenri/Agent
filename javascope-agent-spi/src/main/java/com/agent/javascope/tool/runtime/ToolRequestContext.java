package com.agent.javascope.tool.runtime;

/** 跨同一 Agent 请求中全部工具调用共享的截止时间与重试预算。 */
public record ToolRequestContext(
        String executionId,
        RequestDeadline deadline,
        RetryBudget retryBudget) {

    public ToolRequestContext {
        executionId = executionId == null ? "" : executionId;
        deadline = deadline == null ? RequestDeadline.unbounded() : deadline;
        retryBudget = retryBudget == null ? RetryBudget.unlimited() : retryBudget;
    }

    public static ToolRequestContext unbounded() {
        return new ToolRequestContext("", RequestDeadline.unbounded(), RetryBudget.unlimited());
    }
}
