package com.agent.javascope.tool.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** 工具调用的统一强类型请求；未知业务入参以 JSON 树保留。 */
public record ToolInvocation(
        String toolName,
        JsonNode input,
        String rawInput,
        String idempotencyKey,
        ToolRequestContext requestContext) {

    public ToolInvocation(String toolName, JsonNode input, String rawInput) {
        this(toolName, input, rawInput, "", ToolRequestContext.unbounded());
    }

    public ToolInvocation(String toolName, JsonNode input, String rawInput, String idempotencyKey) {
        this(toolName, input, rawInput, idempotencyKey, ToolRequestContext.unbounded());
    }

    public ToolInvocation(
            String toolName, JsonNode input, String rawInput, ToolRequestContext requestContext) {
        this(toolName, input, rawInput, "", requestContext);
    }

    public ToolInvocation {
        toolName = toolName == null ? "" : toolName;
        Objects.requireNonNull(input, "input must not be null");
        rawInput = rawInput == null ? "" : rawInput;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        requestContext = requestContext == null ? ToolRequestContext.unbounded() : requestContext;
    }
}
