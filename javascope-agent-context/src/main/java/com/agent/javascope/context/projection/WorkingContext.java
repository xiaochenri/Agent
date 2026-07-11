package com.agent.javascope.context.projection;

import com.fasterxml.jackson.databind.JsonNode;

/** 供模型决策的通用上下文投影，不绑定 ReAct、计划或业务领域类型。 */
public record WorkingContext(
        /** 当前任务的计划快照。 */
        JsonNode currentPlan,
        /** 从完整日志中选出的相关历史事件。 */
        JsonNode relevantHistory,
        /** 本轮必须遵守的失败反馈、短期记忆和风险约束。 */
        JsonNode activeConstraints,
        /** 从相关历史中提取的轻量证据索引或摘要。 */
        JsonNode evidenceSummaries) {}
