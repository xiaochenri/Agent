package com.agent.javascope.user.memory;

import java.time.LocalDateTime;

public record UserMemory(
        String userId,
        String namespace,
        String scopeType,
        String scopeId,
        String key,
        String value,
        String source,
        LocalDateTime updatedAt,
        LocalDateTime expireAt) {
}
