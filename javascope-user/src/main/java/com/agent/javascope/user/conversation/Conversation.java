package com.agent.javascope.user.conversation;

import java.time.LocalDateTime;

public record Conversation(
        String id,
        String userId,
        String businessCode,
        String title,
        String status,
        String summary,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
