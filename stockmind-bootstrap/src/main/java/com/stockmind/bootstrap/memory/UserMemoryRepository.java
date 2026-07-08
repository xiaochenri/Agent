package com.stockmind.bootstrap.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserMemoryRepository {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS user_memory (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                user_id VARCHAR(64) NOT NULL,
                mem_key VARCHAR(64) NOT NULL,
                mem_value JSON NOT NULL,
                scope VARCHAR(32) NOT NULL DEFAULT 'global',
                source VARCHAR(16) NOT NULL DEFAULT 'explicit',
                confidence DECIMAL(3,2) NOT NULL DEFAULT 1.00,
                expire_at DATETIME NULL,
                updated_at DATETIME NOT NULL,
                created_at DATETIME NOT NULL,
                UNIQUE KEY uk_user_key_scope (user_id, mem_key, scope),
                KEY idx_user_updated (user_id, updated_at)
            )
            """;

    private static final RowMapper<UserMemoryEntry> ROW_MAPPER = (rs, rowNum) -> new UserMemoryEntry(
            rs.getString("user_id"),
            rs.getString("mem_key"),
            rs.getString("mem_value"),
            rs.getString("scope"),
            rs.getString("source"),
            rs.getTimestamp("updated_at").toLocalDateTime(),
            rs.getTimestamp("expire_at") == null ? null : rs.getTimestamp("expire_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public UserMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initTable() {
        jdbcTemplate.execute(CREATE_TABLE_SQL);
    }

    public void upsert(String userId, String key, Object value, String scope, String source, LocalDateTime expireAt) {
        LocalDateTime now = LocalDateTime.now();
        String memValue = toJson(value);
        jdbcTemplate.update("""
                        INSERT INTO user_memory(user_id, mem_key, mem_value, scope, source, confidence, expire_at, updated_at, created_at)
                        VALUES (?, ?, CAST(? AS JSON), ?, ?, 1.00, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            mem_value = VALUES(mem_value),
                            source = VALUES(source),
                            expire_at = VALUES(expire_at),
                            updated_at = VALUES(updated_at)
                        """,
                userId,
                key,
                memValue,
                scope,
                source,
                toTimestamp(expireAt),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now));
    }

    public List<UserMemoryEntry> listByUser(String userId) {
        return jdbcTemplate.query("""
                        SELECT user_id, mem_key, mem_value, scope, source, updated_at, expire_at
                        FROM user_memory
                        WHERE user_id = ?
                          AND (expire_at IS NULL OR expire_at > NOW())
                        ORDER BY updated_at DESC
                        """,
                ROW_MAPPER,
                userId);
    }

    public List<UserMemoryEntry> listByUserAndKeys(String userId, List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", keys.stream().map(ignored -> "?").toList());
        Object[] params = new Object[keys.size() + 1];
        params[0] = userId;
        for (int i = 0; i < keys.size(); i++) {
            params[i + 1] = keys.get(i);
        }
        return jdbcTemplate.query("""
                        SELECT user_id, mem_key, mem_value, scope, source, updated_at, expire_at
                        FROM user_memory
                        WHERE user_id = ?
                          AND mem_key IN (%s)
                          AND (expire_at IS NULL OR expire_at > NOW())
                        ORDER BY updated_at DESC
                        """.formatted(placeholders),
                ROW_MAPPER,
                params);
    }

    public void deleteByUserAndKey(String userId, String key) {
        jdbcTemplate.update("DELETE FROM user_memory WHERE user_id = ? AND mem_key = ?", userId, key);
    }

    private Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("mem_value 无法序列化为 JSON", e);
        }
    }
}
