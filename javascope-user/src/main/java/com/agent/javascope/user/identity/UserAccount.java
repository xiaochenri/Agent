package com.agent.javascope.user.identity;

import java.time.LocalDateTime;

public record UserAccount(
        String id,
        String username,
        String displayName,
        String avatarUrl,
        String email,
        String mobile,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
