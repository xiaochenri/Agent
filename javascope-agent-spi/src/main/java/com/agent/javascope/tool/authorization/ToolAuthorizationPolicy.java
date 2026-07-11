package com.agent.javascope.tool.authorization;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.tool.runtime.ToolInvocation;

/** 根据工具元数据和调用请求决定是否允许执行。 */
public interface ToolAuthorizationPolicy {

    ToolAuthorizationDecision authorize(AgentToolDefinition definition, ToolInvocation invocation);
}
