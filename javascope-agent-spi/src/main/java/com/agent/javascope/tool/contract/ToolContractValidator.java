package com.agent.javascope.tool.contract;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 工具输入输出结构契约校验器。
 * Core 在业务方法执行前后调用，确保 Schema 约束不依赖模型自行遵守。
 */
public interface ToolContractValidator {

    List<String> validateInput(AgentToolDefinition definition, JsonNode input);

    List<String> validateOutput(AgentToolDefinition definition, JsonNode output);
}
