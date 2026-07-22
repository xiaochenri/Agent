package com.agent.javascope.user.infrastructure;

import com.agent.javascope.user.conversation.Conversation;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConversationRepository {

    private static final RowMapper<Conversation> MAPPER = (rs, rowNum) -> new Conversation(
            rs.getString("id"), rs.getString("user_id"), rs.getString("business_code"),
            rs.getString("title"), rs.getString("status"), rs.getString("summary"),
            toLocalDateTime(rs.getTimestamp("last_message_at")),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public JdbcConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS conversation (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64) NOT NULL,
                    business_code VARCHAR(32) NOT NULL,
                    title VARCHAR(255) NULL,
                    status VARCHAR(16) NOT NULL,
                    summary TEXT NULL,
                    summary_message_seq BIGINT NOT NULL DEFAULT 0,
                    last_message_at DATETIME NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    KEY idx_conv_user_business (user_id, business_code, updated_at)
                )
                """);
    }

    public Conversation createIfAbsent(String id, String userId, String businessCode, String title) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT IGNORE INTO conversation(
                    id, user_id, business_code, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'active', ?, ?)
                """, id, userId, businessCode, title, Timestamp.valueOf(now), Timestamp.valueOf(now));
        return findOwned(id, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "conversation id is already owned by another user"));
    }

    public Optional<Conversation> findOwned(String id, String userId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, business_code, title, status, summary,
                       last_message_at, created_at, updated_at
                FROM conversation
                WHERE id = ? AND user_id = ? AND status <> 'deleted'
                """, MAPPER, id, userId).stream().findFirst();
    }

    public List<Conversation> listOwned(String userId, String businessCode, int limit) {
        return listOwned(userId, businessCode, null, null, limit);
    }

    /** Keyset pagination keeps the conversation sidebar responsive as history grows. */
    public List<Conversation> listOwned(
            String userId, String businessCode, LocalDateTime beforeUpdatedAt, String beforeId, int limit) {
        Timestamp before = beforeUpdatedAt == null ? null : Timestamp.valueOf(beforeUpdatedAt);
        if (businessCode == null || businessCode.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT id, user_id, business_code, title, status, summary,
                           last_message_at, created_at, updated_at
                    FROM conversation
                    WHERE user_id = ? AND status <> 'deleted'
                      AND NOT (title IS NULL AND last_message_at IS NULL)
                      AND (? IS NULL OR updated_at < ? OR (updated_at = ? AND id < ?))
                    ORDER BY updated_at DESC, id DESC
                    LIMIT ?
                    """, MAPPER, userId, before, before, before, beforeId, limit);
        }
        return jdbcTemplate.query("""
                SELECT id, user_id, business_code, title, status, summary,
                       last_message_at, created_at, updated_at
                FROM conversation
                WHERE user_id = ? AND business_code = ? AND status <> 'deleted'
                  AND NOT (title IS NULL AND last_message_at IS NULL)
                  AND (? IS NULL OR updated_at < ? OR (updated_at = ? AND id < ?))
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """, MAPPER, userId, businessCode, before, before, before, beforeId, limit);
    }

    public void touch(String id, String title) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE conversation
                SET title = COALESCE(title, ?), last_message_at = ?, updated_at = ?, version = version + 1
                WHERE id = ?
                """, title, Timestamp.valueOf(now), Timestamp.valueOf(now), id);
    }

    public void archive(String id, String userId) {
        jdbcTemplate.update("UPDATE conversation SET status = 'archived', updated_at = NOW() WHERE id = ? AND user_id = ?",
                id, userId);
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
