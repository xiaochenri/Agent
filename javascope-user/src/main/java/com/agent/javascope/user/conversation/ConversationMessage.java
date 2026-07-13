package com.agent.javascope.user.conversation;

import java.time.LocalDateTime;

public record ConversationMessage(
        long id,
        String messageId,
        String conversationId,
        String userId,
        String requestId,
        String role,
        String content,
        String status,
        String executionId,
        String metadata,
        LocalDateTime createdAt) {
}
