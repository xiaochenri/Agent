package com.agent.javascope.context.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** 供模型决策的通用上下文投影，不绑定 ReAct、计划或业务领域类型。 */
public record WorkingContext(
        /** 当前任务的计划快照。 */
        JsonNode currentPlan,
        /** ReAct 跨轮调查状态；属于不可裁剪的核心决策上下文。 */
        JsonNode investigationState,
        /** 从完整日志中选出的相关历史事件。 */
        JsonNode relevantHistory,
        /** 本轮必须遵守的失败反馈、短期记忆和风险约束。 */
        JsonNode activeConstraints,
        /** 从相关历史中提取的轻量证据索引或摘要。 */
        JsonNode evidenceSummaries,
        /** 每个已调用工具最近一次结果的结构化摘要，避免关键观察被普通历史挤出窗口。 */
        JsonNode latestObservations,
        /** 所有尚未解除的最终工具失败，不按工具名去重，也不受历史窗口挤压。 */
        JsonNode activeToolFailures) {

    /** 兼容尚未传入活跃失败区域的上下文管理器。 */
    public WorkingContext(
            JsonNode currentPlan,
            JsonNode relevantHistory,
            JsonNode activeConstraints,
            JsonNode evidenceSummaries,
            JsonNode latestObservations) {
        this(currentPlan, JsonNodeFactory.instance.objectNode(), relevantHistory, activeConstraints, evidenceSummaries,
                latestObservations, JsonNodeFactory.instance.arrayNode());
    }

    /** 兼容最初仅提供历史、约束和证据摘要的上下文管理器。 */
    public WorkingContext(
            JsonNode currentPlan,
            JsonNode relevantHistory,
            JsonNode activeConstraints,
            JsonNode evidenceSummaries) {
        this(currentPlan, JsonNodeFactory.instance.objectNode(), relevantHistory, activeConstraints, evidenceSummaries,
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode());
    }
}
