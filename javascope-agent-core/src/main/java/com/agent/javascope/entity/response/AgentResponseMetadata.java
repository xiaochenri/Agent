package com.agent.javascope.entity.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 响应观测元数据。
 */
public class AgentResponseMetadata {

    /** 模型服务提供方。 */
    private String provider = "";
    /** 模型名称。 */
    private String model = "";
    /** 可用于查询完整执行轨迹的标识。 */
    @JsonProperty("execution_id")
    private String executionId = "";
    /** 响应创建时间，ISO-8601 字符串。 */
    @JsonProperty("created_at")
    private String createdAt = "";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider == null ? "" : provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId == null ? "" : executionId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt == null ? "" : createdAt;
    }
}
