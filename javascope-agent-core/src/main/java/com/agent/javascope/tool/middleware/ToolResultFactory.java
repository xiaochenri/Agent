package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolExecutionStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;

/** Middleware 生成统一失败结果的内部辅助类。 */
final class ToolResultFactory {

    private ToolResultFactory() {}

    static ToolExecutionResult failed(String tool, String code, String message, boolean retryable) {
        return new ToolExecutionResult(
                tool,
                ToolExecutionStatus.FAILED,
                false,
                List.of(),
                List.of(message),
                retryable,
                code,
                NullNode.getInstance(),
                JsonNodeFactory.instance.objectNode());
    }
}
