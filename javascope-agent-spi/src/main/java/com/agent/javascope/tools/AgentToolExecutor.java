package com.agent.javascope.tools;

import com.agent.javascope.entity.AgentToolDefinition;
import java.util.Map;
import java.util.List;

public interface AgentToolExecutor {

    /** 执行指定工具，并返回统一 JSON 字符串结果。 */
    String execute(String tool, Map<String, Object> input, String rawInput);

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
