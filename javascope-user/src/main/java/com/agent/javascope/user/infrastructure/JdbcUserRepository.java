package com.agent.javascope.user.infrastructure;

import com.agent.javascope.user.identity.UserAccount;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository {

    private static final RowMapper<UserAccount> MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
            rs.getString("avatar_url"), rs.getString("email"), rs.getString("mobile"),
            rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_user (
                    id VARCHAR(64) PRIMARY KEY,
                    username VARCHAR(64) NULL,
                    display_name VARCHAR(128) NULL,
                    avatar_url VARCHAR(512) NULL,
                    email VARCHAR(128) NULL,
                    mobile VARCHAR(32) NULL,
                    status VARCHAR(16) NOT NULL,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    UNIQUE KEY uk_app_user_username (username),
                    UNIQUE KEY uk_app_user_email (email)
                )
                """);
    }

    public void ensureExists(String userId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO app_user(id, status, created_at, updated_at)
                VALUES (?, 'active', ?, ?)
                ON DUPLICATE KEY UPDATE updated_at = updated_at
                """, userId, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public Optional<UserAccount> findById(String userId) {
        return jdbcTemplate.query("""
                SELECT id, username, display_name, avatar_url, email, mobile,
                       status, created_at, updated_at
                FROM app_user WHERE id = ?
                """, MAPPER, userId).stream().findFirst();
    }

    public UserAccount updateProfile(String userId, String displayName, String avatarUrl) {
        ensureExists(userId);
        jdbcTemplate.update("""
                UPDATE app_user SET display_name = ?, avatar_url = ?, updated_at = NOW()
                WHERE id = ?
                """, displayName, avatarUrl, userId);
        return findById(userId).orElseThrow();
    }
}
