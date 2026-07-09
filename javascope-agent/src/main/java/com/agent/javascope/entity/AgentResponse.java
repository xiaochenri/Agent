package com.agent.javascope.entity;

/**
 * Agent Runtime 对外响应协议。
 */
public class AgentResponse {

    /** 执行状态：completed、blocked 或 failed。 */
    private String status = "completed";
    /** 路由类型：task、chat 或 meta。 */
    private String route = "task";
    /** 用户默认可见回复。 */
    private AgentUserMessage message = new AgentUserMessage();
    /** 业务结构化结果。 */
    private AgentResponseData data = new AgentResponseData();
    /** 内部运行时视图。 */
    private AgentRuntimeView runtime = new AgentRuntimeView();
    /** 响应元数据。 */
    private AgentResponseMetadata metadata = new AgentResponseMetadata();

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status == null || status.isBlank() ? "completed" : status;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route == null || route.isBlank() ? "task" : route;
    }

    public AgentUserMessage getMessage() {
        return message;
    }

    public void setMessage(AgentUserMessage message) {
        this.message = message == null ? new AgentUserMessage() : message;
    }

    public AgentResponseData getData() {
        return data;
    }

    public void setData(AgentResponseData data) {
        this.data = data == null ? new AgentResponseData() : data;
    }

    public AgentRuntimeView getRuntime() {
        return runtime;
    }

    public void setRuntime(AgentRuntimeView runtime) {
        this.runtime = runtime == null ? new AgentRuntimeView() : runtime;
    }

    public AgentResponseMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(AgentResponseMetadata metadata) {
        this.metadata = metadata == null ? new AgentResponseMetadata() : metadata;
    }
}
