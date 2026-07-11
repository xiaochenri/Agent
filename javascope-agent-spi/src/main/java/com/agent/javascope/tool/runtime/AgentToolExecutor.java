package com.agent.javascope.tool.runtime;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import java.util.List;
import java.util.Map;

public interface AgentToolExecutor {

    /** 执行指定工具，并返回统一的强类型结果。 */
    ToolExecutionResult execute(ToolInvocation invocation);

    /** 返回可暴露给模型的工具 schema 列表。 */
    List<Map<String, Object>> listToolSchemas();

    /** 返回完整工具定义，包括模型不可见的 runtime 内部工具。 */
    default List<AgentToolDefinition> listToolDefinitions() {
        return List.of();
    }

    /** 按工具名称查询定义，用于执行前治理和计划步骤校验。 */
    default AgentToolDefinition getToolDefinition(String name) {
        return null;
    }
}
