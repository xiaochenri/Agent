package com.agent.javascope.entity;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentToolCall {

    /** 模型请求调用的工具名称。 */
    private String name = "";
    /** 工具调用入参。不同工具入参结构不同，因此保留为动态对象。 */
    private Map<String, Object> input = new LinkedHashMap<>();

    public AgentToolCall() {}

    public AgentToolCall(String name, Map<String, Object> input) {
        this.name = name;
        this.input = input == null ? new LinkedHashMap<>() : input;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public Map<String, Object> getInput() {
        return input == null ? new LinkedHashMap<>() : input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input == null ? new LinkedHashMap<>() : input;
    }
}
