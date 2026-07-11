package com.agent.javascope.model;

/** 已完成 prompt 组装、等待模型执行的请求。 */
public record ModelRequest(String prompt) {

    public ModelRequest {
        prompt = prompt == null ? "" : prompt;
    }
}
