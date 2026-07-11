package com.agent.javascope.tool.registry;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import java.util.List;
import java.util.Map;

/** 负责工具发现、注册和定义查询，不参与授权或实际执行。 */
public interface ToolRegistry {

    AgentToolDefinition findDefinition(String toolName);

    List<AgentToolDefinition> listDefinitions();

    List<Map<String, Object>> listModelVisibleSchemas();
}
