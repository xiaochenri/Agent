package com.agent.javascope.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 响应观测元数据。
 */
public class AgentResponseMetadata {

    /** 模型服务提供方。 */
    private String provider = "";
    /** 模型名称。 */
    private String model = "";
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

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt == null ? "" : createdAt;
    }
}
