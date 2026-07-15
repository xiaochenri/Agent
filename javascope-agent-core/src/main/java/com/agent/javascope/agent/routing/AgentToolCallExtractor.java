package com.agent.javascope.agent.routing;

import com.agent.javascope.entity.execution.AgentToolCall;
import com.agent.javascope.json.AgentJsonCodecUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从模型响应中提取工具调用，并统一规整为 AgentToolCall。
 */
public class AgentToolCallExtractor {

    /** JSON 辅助工具，用于把模型输出中的动态对象安全转换为 Map/List。 */
    private final AgentJsonCodecUtil json;

    public AgentToolCallExtractor(AgentJsonCodecUtil json) {
        this.json = json;
    }

    /**
     * direct/react 优先读取单动作协议 selected_action.tool_call；
     * planned 和旧模型响应继续兼容 tool_calls 数组。
     */
    public List<AgentToolCall> extract(Map<String, Object> response) {
        Map<String, Object> selectedAction = json.asMap(response.get("selected_action"));
        String actionType = selectedAction.get("type") instanceof String type ? type.trim() : "";
        if ("tool_call".equals(actionType)) {
            AgentToolCall call = toToolCall(json.asMap(selectedAction.get("tool_call")));
            return call == null ? List.of() : List.of(call);
        }
        if ("final_answer".equals(actionType)) {
            return List.of();
        }

        List<AgentToolCall> result = new ArrayList<>();
        Object toolCallsObj = response.get("tool_calls");
        if (!(toolCallsObj instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            AgentToolCall call = toToolCall(json.asMap(item));
            if (call != null) result.add(call);
        }
        return result;
    }

    public boolean usesSingleActionProtocol(Map<String, Object> response) {
        Map<String, Object> selectedAction = json.asMap(response == null ? null : response.get("selected_action"));
        return "tool_call".equals(selectedAction.get("type"))
                || "final_answer".equals(selectedAction.get("type"));
    }

    private AgentToolCall toToolCall(Map<String, Object> call) {
        String name = call.get("name") instanceof String text ? json.normalize(text, "") : "";
        if (name.isEmpty()) return null;
        return new AgentToolCall(name, json.asMap(call.get("input")));
    }
}
