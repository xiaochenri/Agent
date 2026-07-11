package com.agent.javascope.tool.invocation;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;

/** 按已注册工具的底层协议执行实际动作。 */
public interface ToolInvoker {

    ToolExecutionResult invoke(AgentToolDefinition definition, ToolInvocation invocation);
}
