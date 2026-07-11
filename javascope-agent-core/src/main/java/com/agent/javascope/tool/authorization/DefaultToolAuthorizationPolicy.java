package com.agent.javascope.tool.authorization;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.tool.annotation.ToolDangerLevel;
import com.agent.javascope.tool.runtime.ToolInvocation;

/** 默认安全策略：需要确认或高危的工具不会被模型直接执行。 */
public class DefaultToolAuthorizationPolicy implements ToolAuthorizationPolicy {

    @Override
    public ToolAuthorizationDecision authorize(AgentToolDefinition definition, ToolInvocation invocation) {
        if (definition.isRequiresConfirmation()) {
            return ToolAuthorizationDecision.requireConfirmation("tool requires user confirmation: " + definition.getName());
        }
        if (definition.getDangerLevel() == ToolDangerLevel.HIGH
                || definition.getDangerLevel() == ToolDangerLevel.CRITICAL) {
            return ToolAuthorizationDecision.requireConfirmation("high-risk tool requires user confirmation: " + definition.getName());
        }
        return ToolAuthorizationDecision.allow();
    }
}
