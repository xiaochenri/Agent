package com.agent.javascope.user.port;

import com.agent.javascope.user.conversation.ConversationMessage;
import java.util.List;
import java.util.Map;

public record AgentChatCommand(
        String requestId,
        String userId,
        String conversationId,
        String input,
        List<ConversationMessage> history,
        Map<String, Object> globalMemory,
        Map<String, Object> businessMemory) {
}
