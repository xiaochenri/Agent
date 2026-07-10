package com.agent.javascope.agent;

import com.agent.javascope.entity.AgentToolCall;
import com.agent.javascope.util.AgentJsonCodecUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从模型响应中提取工具调用，并统一规整为 AgentToolCall。
 */
class AgentToolCallExtractor {

    /** JSON 辅助工具，用于把模型输出中的动态对象安全转换为 Map/List。 */
    private final AgentJsonCodecUtil json;

    AgentToolCallExtractor(AgentJsonCodecUtil json) {
        this.json = json;
    }

    /**
     * 读取响应里的 tool_calls 字段；字段缺失、类型不对或工具名为空时直接忽略。
     */
    List<AgentToolCall> extract(Map<String, Object> response) {
        List<AgentToolCall> result = new ArrayList<>();
        Object toolCallsObj = response.get("tool_calls");
        if (!(toolCallsObj instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            Map<String, Object> call = json.asMap(item);
            if (json.normalize((String) call.get("name"), "").isEmpty()) {
                continue;
            }
            result.add(new AgentToolCall(
                    json.normalize((String) call.get("name"), ""),
                    json.asMap(call.get("input"))));
        }
        return result;
    }
}
