package com.agent.javascope.user.infrastructure;

import com.agent.javascope.user.conversation.ConversationMessage;
import jakarta.annotation.PostConstruct;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConversationMessageRepository {

    private static final RowMapper<ConversationMessage> MAPPER = (rs, rowNum) -> new ConversationMessage(
            rs.getLong("id"), rs.getString("message_id"), rs.getString("conversation_id"),
            rs.getString("user_id"), rs.getString("request_id"), rs.getString("message_role"),
            rs.getString("content"), rs.getString("status"), rs.getString("execution_id"),
            rs.getString("metadata"), rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public JdbcConversationMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS conversation_message (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    message_id VARCHAR(64) NOT NULL,
                    conversation_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(64) NOT NULL,
                    request_id VARCHAR(64) NULL,
                    message_role VARCHAR(16) NOT NULL,
                    content MEDIUMTEXT NOT NULL,
                    content_type VARCHAR(32) NOT NULL DEFAULT 'text',
                    status VARCHAR(16) NOT NULL,
                    execution_id VARCHAR(64) NULL,
                    metadata JSON NULL,
                    created_at DATETIME NOT NULL,
                    UNIQUE KEY uk_conversation_message_id (message_id),
                    UNIQUE KEY uk_conv_request_role (conversation_id, request_id, message_role),
                    KEY idx_message_conv_id (conversation_id, id)
                )
                """);
    }

    public ConversationMessage insert(
            String messageId, String conversationId, String userId, String requestId,
            String role, String content, String status, String executionId, String metadata) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversation_message(
                        message_id, conversation_id, user_id, request_id, message_role, content,
                        status, execution_id, metadata, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, messageId);
            statement.setString(2, conversationId);
            statement.setString(3, userId);
            statement.setString(4, requestId);
            statement.setString(5, role);
            statement.setString(6, content);
            statement.setString(7, status);
            statement.setString(8, executionId);
            statement.setString(9, metadata == null ? "{}" : metadata);
            statement.setTimestamp(10, Timestamp.valueOf(now));
            return statement;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
        return new ConversationMessage(id, messageId, conversationId, userId, requestId, role,
                content, status, executionId, metadata == null ? "{}" : metadata, now);
    }

    public Optional<ConversationMessage> findCompletedAssistant(String conversationId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                SELECT id, message_id, conversation_id, user_id, request_id, message_role, content,
                       status, execution_id, metadata, created_at
                FROM conversation_message
                WHERE conversation_id = ? AND request_id = ? AND message_role = 'assistant' AND status = 'completed'
                """, MAPPER, conversationId, requestId).stream().findFirst();
    }

    public Optional<ConversationMessage> findUserByRequestId(String conversationId, String requestId) {
        if (requestId == null || requestId.isBlank()) return Optional.empty();
        return jdbcTemplate.query("""
                SELECT id, message_id, conversation_id, user_id, request_id, message_role, content,
                       status, execution_id, metadata, created_at
                FROM conversation_message
                WHERE conversation_id = ? AND request_id = ? AND message_role = 'user'
                """, MAPPER, conversationId, requestId).stream().findFirst();
    }

    public List<ConversationMessage> recent(String conversationId, int limit) {
        List<ConversationMessage> result = new ArrayList<>(jdbcTemplate.query("""
                SELECT id, message_id, conversation_id, user_id, request_id, message_role, content,
                       status, execution_id, metadata, created_at
                FROM conversation_message
                WHERE conversation_id = ? AND status = 'completed'
                ORDER BY id DESC
                LIMIT ?
                """, MAPPER, conversationId, limit));
        Collections.reverse(result);
        return result;
    }

    public List<ConversationMessage> list(String conversationId, long beforeId, int limit) {
        long cursor = beforeId <= 0 ? Long.MAX_VALUE : beforeId;
        return jdbcTemplate.query("""
                SELECT id, message_id, conversation_id, user_id, request_id, message_role, content,
                       status, execution_id, metadata, created_at
                FROM conversation_message
                WHERE conversation_id = ? AND id < ?
                ORDER BY id DESC
                LIMIT ?
                """, MAPPER, conversationId, cursor, limit);
    }
}
