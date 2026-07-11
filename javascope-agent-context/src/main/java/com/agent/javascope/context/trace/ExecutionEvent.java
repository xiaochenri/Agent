package com.agent.javascope.context.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** 可回放的单次执行事件，保存完整调试数据。 */
public record ExecutionEvent(
        /** 关联同一次 Agent 调用的唯一标识。 */
        String executionId,
        /** 在同一 executionId 内单调递增的事件序号。 */
        long sequence,
        /** 事件被记录的时间点。 */
        Instant occurredAt,
        /** 事件的生命周期类型。 */
        ExecutionEventType type,
        /** 完整输入载荷，用于调试和回放。 */
        JsonNode input,
        /** 完整输出载荷，用于调试和回放。 */
        JsonNode output) {}
