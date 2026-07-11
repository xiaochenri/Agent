package com.agent.javascope.context.projection;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Core 向上下文模块提供的通用状态快照。 */
public record ContextRequest(
        /** 当前生效计划的通用 JSON 表示。 */
        JsonNode currentPlan,
        /** 当前执行的完整日志快照；上下文管理器只会选取其中相关片段。 */
        JsonNode executionLog,
        /** 仅供下一轮使用的短期记忆或提示。 */
        JsonNode ephemeralMemory,
        /** 最近一次校验或执行失败生成的修正约束。 */
        String validationFeedback,
        /** 当前执行过程中累积的风险标记。 */
        JsonNode riskFlags) {

    /** 将可选文本归一化，并确保所有 JSON 载荷均存在。 */
    public ContextRequest {
        Objects.requireNonNull(currentPlan, "currentPlan must not be null");
        Objects.requireNonNull(executionLog, "executionLog must not be null");
        Objects.requireNonNull(ephemeralMemory, "ephemeralMemory must not be null");
        validationFeedback = validationFeedback == null ? "" : validationFeedback;
        Objects.requireNonNull(riskFlags, "riskFlags must not be null");
    }
}
