package com.stockmind.bootstrap;

import com.agent.javascope.agent.runtime.ReActAgent;
import com.agent.javascope.user.conversation.ConversationMessage;
import com.agent.javascope.user.port.AgentChatCommand;
import com.agent.javascope.user.port.AgentChatResult;
import com.agent.javascope.user.port.BusinessAgentHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class StockBusinessAgentHandler implements BusinessAgentHandler {

    private final ReActAgent agent;
    private final ObjectMapper objectMapper;

    public StockBusinessAgentHandler(ReActAgent agent, ObjectMapper objectMapper) {
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    @Override
    public String businessCode() {
        return "stock";
    }

    @Override
    public AgentChatResult chat(AgentChatCommand command) {
        String rawReply = agent.call(buildPrompt(command), command.conversationId(), command.userId());
        return toResult(rawReply);
    }

    @Override
    public AgentChatResult chatStream(
            AgentChatCommand command, Consumer<Map<String, Object>> eventConsumer) {
        String rawReply = agent.callWithModelStream(
                buildPrompt(command), command.conversationId(), command.userId(), eventConsumer);
        return toResult(rawReply);
    }

    private AgentChatResult toResult(String rawReply) {
        Map<String, Object> payload = parsePayload(rawReply);
        String reply = extractReply(payload, rawReply);
        return new AgentChatResult(reply, extractExecutionId(payload), payload);
    }

    private String buildPrompt(AgentChatCommand command) {
        StringBuilder prompt = new StringBuilder();
        appendMemory(prompt, "用户全局记忆", command.globalMemory());
        appendMemory(prompt, "股票业务记忆", command.businessMemory());
        if (!command.history().isEmpty()) {
            prompt.append("以下是同一会话最近的历史消息，请保持上下文一致：\n");
            for (ConversationMessage message : command.history()) {
                prompt.append(message.role()).append(": ").append(message.content()).append('\n');
            }
        }
        prompt.append("本轮用户: ").append(command.input());
        return prompt.toString();
    }

    private void appendMemory(StringBuilder prompt, String label, Map<String, Object> memory) {
        if (memory == null || memory.isEmpty()) {
            return;
        }
        prompt.append(label).append("，请在适用时遵循：\n");
        memory.forEach((key, value) -> prompt.append(key).append('=').append(value).append('\n'));
    }

    private Map<String, Object> parsePayload(String rawReply) {
        try {
            return objectMapper.readValue(rawReply, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return Map.of("raw_reply", rawReply == null ? "" : rawReply);
        }
    }

    private String extractReply(Map<String, Object> payload, String fallback) {
        Object message = payload.get("message");
        if (message instanceof Map<?, ?> map && map.get("content") != null) {
            return String.valueOf(map.get("content"));
        }
        return fallback == null ? "" : fallback;
    }

    private String extractExecutionId(Map<String, Object> payload) {
        Object metadata = payload.get("metadata");
        if (metadata instanceof Map<?, ?> map && map.get("execution_id") != null) {
            return String.valueOf(map.get("execution_id"));
        }
        return null;
    }
}
