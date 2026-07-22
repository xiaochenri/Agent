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
        Integer passwordHashColumn = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE table_schema = DATABASE() AND table_name = 'app_user' AND column_name = 'password_hash'
                """, Integer.class);
        if (passwordHashColumn == null || passwordHashColumn == 0) {
            jdbcTemplate.execute("ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(255) NULL");
        }
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

    public Optional<UserAccount> findByUsername(String username) {
        return jdbcTemplate.query("""
                SELECT id, username, display_name, avatar_url, email, mobile,
                       status, created_at, updated_at
                FROM app_user WHERE username = ?
                """, MAPPER, username).stream().findFirst();
    }

    public Optional<String> passwordHash(String userId) {
        return jdbcTemplate.query("SELECT password_hash FROM app_user WHERE id = ?", (rs, rowNum) ->
                rs.getString("password_hash"), userId).stream().findFirst();
    }

    public UserAccount create(String userId, String username, String displayName, String passwordHash) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO app_user(id, username, display_name, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'active', ?, ?)
                """, userId, username, displayName, passwordHash, Timestamp.valueOf(now), Timestamp.valueOf(now));
        return findById(userId).orElseThrow();
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
