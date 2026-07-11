package com.agent.javascope.tool.middleware;

import com.agent.javascope.contract.tool.AgentToolDefinition;

/** 工具治理链共享的只读上下文。 */
public record ToolExecutionContext(String invocationId, AgentToolDefinition definition) {}
