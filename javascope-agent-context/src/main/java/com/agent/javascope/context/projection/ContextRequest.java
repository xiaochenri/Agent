package com.agent.javascope.context.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

/** Core 向上下文模块提供的通用状态快照。 */
public record ContextRequest(
        /** 当前生效计划的通用 JSON 表示。 */
        JsonNode currentPlan,
        /** 当前执行的完整日志快照；上下文管理器只会选取其中相关片段。 */
        JsonNode executionLog,
        /** 仅供下一轮使用的短期记忆或提示。 */
        JsonNode ephemeralMemory,
        /** 业务工具上报的通用决策状态，应优先于普通历史日志展示。 */
        JsonNode businessDecisions,
        /** 尚未解除的最终工具失败；必须独立于普通历史和最新观察持续传递。 */
        JsonNode activeToolFailures,
        /** 最近一次校验或执行失败生成的修正约束。 */
        String validationFeedback,
        /** 当前执行过程中累积的风险标记。 */
        JsonNode riskFlags) {

    /** 将可选文本归一化，并确保所有 JSON 载荷均存在。 */
    public ContextRequest {
        Objects.requireNonNull(currentPlan, "currentPlan must not be null");
        Objects.requireNonNull(executionLog, "executionLog must not be null");
        Objects.requireNonNull(ephemeralMemory, "ephemeralMemory must not be null");
        Objects.requireNonNull(businessDecisions, "businessDecisions must not be null");
        Objects.requireNonNull(activeToolFailures, "activeToolFailures must not be null");
        validationFeedback = validationFeedback == null ? "" : validationFeedback;
        Objects.requireNonNull(riskFlags, "riskFlags must not be null");
    }

    /** 兼容尚未提供活跃工具失败区域的既有上下文调用方。 */
    public ContextRequest(
            JsonNode currentPlan,
            JsonNode executionLog,
            JsonNode ephemeralMemory,
            JsonNode businessDecisions,
            String validationFeedback,
            JsonNode riskFlags) {
        this(currentPlan, executionLog, ephemeralMemory, businessDecisions,
                JsonNodeFactory.instance.arrayNode(), validationFeedback, riskFlags);
    }
}
