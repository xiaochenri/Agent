package com.agent.javascope.user.application;

import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserSessionRepository {
    private final JdbcTemplate jdbcTemplate;
    JdbcUserSessionRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }
    @PostConstruct void initialize() { jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS app_user_session (
                token_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL,
                expires_at DATETIME NOT NULL, created_at DATETIME NOT NULL,
                KEY idx_user_session_expiry (expires_at)
            )
            """); }
    void create(String tokenHash, String userId, LocalDateTime expiresAt) { jdbcTemplate.update("INSERT INTO app_user_session(token_hash, user_id, expires_at, created_at) VALUES (?, ?, ?, NOW())", tokenHash, userId, Timestamp.valueOf(expiresAt)); }
    Optional<String> findActiveUserId(String tokenHash) { return jdbcTemplate.query("SELECT user_id FROM app_user_session WHERE token_hash = ? AND expires_at > NOW()", (rs, rowNum) -> rs.getString(1), tokenHash).stream().findFirst(); }
    void delete(String tokenHash) { jdbcTemplate.update("DELETE FROM app_user_session WHERE token_hash = ?", tokenHash); }
}
