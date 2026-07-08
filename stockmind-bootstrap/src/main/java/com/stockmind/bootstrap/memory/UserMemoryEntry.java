package com.stockmind.bootstrap.memory;

import java.time.LocalDateTime;

/**
 * 用户长期记忆条目。
 */
public record UserMemoryEntry(
        // 记忆归属用户 ID
        String userId,
        // 记忆键，例如 language/tone
        String key,
        // 记忆值（JSON 字符串）
        String value,
        // 记忆作用域，当前固定为 global
        String scope,
        // 写入来源，例如 explicit/implicit
        String source,
        // 最近更新时间
        LocalDateTime updatedAt,
        // 过期时间，null 表示不过期
        LocalDateTime expireAt) {
}
