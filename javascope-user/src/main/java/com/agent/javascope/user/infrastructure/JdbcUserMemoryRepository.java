package com.agent.javascope.user.infrastructure;

import com.agent.javascope.user.memory.UserMemory;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserMemoryRepository {

    private static final RowMapper<UserMemory> MAPPER = (rs, rowNum) -> new UserMemory(
            rs.getString("user_id"), rs.getString("namespace"), rs.getString("scope_type"),
            rs.getString("scope_id"), rs.getString("mem_key"), rs.getString("mem_value"),
            rs.getString("source"), rs.getTimestamp("updated_at").toLocalDateTime(),
            rs.getTimestamp("expire_at") == null ? null : rs.getTimestamp("expire_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_user_memory (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id VARCHAR(64) NOT NULL,
                    namespace VARCHAR(32) NOT NULL,
                    scope_type VARCHAR(16) NOT NULL,
                    scope_id VARCHAR(64) NOT NULL DEFAULT '',
                    mem_key VARCHAR(64) NOT NULL,
                    mem_value JSON NOT NULL,
                    source VARCHAR(16) NOT NULL,
                    confidence DECIMAL(3,2) NOT NULL DEFAULT 1.00,
                    expire_at DATETIME NULL,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    UNIQUE KEY uk_agent_memory_scope (
                        user_id, namespace, scope_type, scope_id, mem_key),
                    KEY idx_agent_memory_user (user_id, updated_at)
                )
                """);
        migrateLegacyMemory();
    }

    public void upsert(UserMemory memory) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO agent_user_memory(
                    user_id, namespace, scope_type, scope_id, mem_key, mem_value,
                    source, confidence, expire_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?, 1.00, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    mem_value = VALUES(mem_value), source = VALUES(source),
                    expire_at = VALUES(expire_at), updated_at = VALUES(updated_at)
                """, memory.userId(), memory.namespace(), memory.scopeType(), memory.scopeId(),
                memory.key(), memory.value(), memory.source(), toTimestamp(memory.expireAt()),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public List<UserMemory> list(String userId) {
        return jdbcTemplate.query("""
                SELECT user_id, namespace, scope_type, scope_id, mem_key, mem_value,
                       source, updated_at, expire_at
                FROM agent_user_memory
                WHERE user_id = ? AND (expire_at IS NULL OR expire_at > NOW())
                ORDER BY updated_at DESC
                """, MAPPER, userId);
    }

    public List<UserMemory> listForContext(String userId, String namespace, String businessCode) {
        return jdbcTemplate.query("""
                SELECT user_id, namespace, scope_type, scope_id, mem_key, mem_value,
                       source, updated_at, expire_at
                FROM agent_user_memory
                WHERE user_id = ?
                  AND namespace = ?
                  AND ((scope_type = 'global' AND scope_id = '')
                       OR (scope_type = 'business' AND scope_id = ?))
                  AND (expire_at IS NULL OR expire_at > NOW())
                ORDER BY scope_type, updated_at DESC
                """, MAPPER, userId, namespace, businessCode);
    }

    public void delete(String userId, String namespace, String scopeType, String scopeId, String key) {
        jdbcTemplate.update("""
                DELETE FROM agent_user_memory
                WHERE user_id = ? AND namespace = ? AND scope_type = ? AND scope_id = ? AND mem_key = ?
                """, userId, namespace, scopeType, scopeId, key);
    }

    private Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private void migrateLegacyMemory() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'user_memory'
                """, Integer.class);
        if (tableCount == null || tableCount == 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT IGNORE INTO agent_user_memory(
                    user_id, namespace, scope_type, scope_id, mem_key, mem_value,
                    source, confidence, expire_at, created_at, updated_at)
                SELECT user_id, 'assistant',
                       CASE WHEN mem_key = 'business_preferences' THEN 'business' ELSE 'global' END,
                       CASE WHEN mem_key = 'business_preferences' THEN 'stock' ELSE '' END,
                       mem_key, mem_value, source, confidence, expire_at, created_at, updated_at
                FROM user_memory
                """);
    }
}
