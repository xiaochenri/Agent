package com.agent.javascope.tool.contract;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 业务工具语义校验扩展点。
 * JSON Schema 负责结构，本接口负责期间与类型一致性、计算口径、数据来源等业务规则。
 */
public interface ToolSemanticValidator {

    boolean supports(String toolName);

    default List<String> validateInput(AgentToolDefinition definition, JsonNode input) {
        return List.of();
    }

    default List<String> validateOutput(
            AgentToolDefinition definition, JsonNode normalizedInput, ToolExecutionResult result) {
        return List.of();
    }
}
