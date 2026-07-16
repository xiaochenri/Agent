package com.agent.javascope.tool.runtime;

/** 将兼容错误码或未预期异常转换为统一 ToolError。 */
public interface ToolErrorClassifier {

    /**
     * 分类框架内置错误码。
     *
     * @param code 框架稳定错误码
     * @param publicMessage 已确认可向 Agent 展示的消息
     * @param retryable 错误在能力上是否可重试
     * @return 完整的结构化错误
     */
    default ToolError classify(ToolErrorCode code, String publicMessage, boolean retryable) {
        return classify(code == null ? ToolErrorCode.TOOL_EXECUTION_FAILED.code() : code.code(),
                publicMessage, retryable);
    }

    /** 分类业务域或兼容协议提供的字符串错误码。 */
    ToolError classify(String code, String publicMessage, boolean retryable);

    /** 展开并分类未预期异常，不使用原始异常消息作为公开消息。 */
    ToolError classify(Throwable error);
}
