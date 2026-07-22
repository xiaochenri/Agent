package com.agent.javascope.user.application;

import com.agent.javascope.user.identity.UserAccount;
import com.agent.javascope.user.infrastructure.JdbcUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

/** Local-account authentication. Passwords are PBKDF2-hashed; browser sessions store only a random token. */
@Service
public class UserAuthenticationService {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcUserRepository users;
    private final JdbcUserSessionRepository sessions;

    public UserAuthenticationService(JdbcUserRepository users, JdbcUserSessionRepository sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    public LoginResult register(String username, String password, String displayName) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        if (users.findByUsername(normalizedUsername).isPresent()) {
            throw new IllegalArgumentException("username is already in use");
        }
        UserAccount user = users.create(UUID.randomUUID().toString(), normalizedUsername,
                normalizeDisplayName(displayName, normalizedUsername), hash(password));
        return new LoginResult(user, createSession(user.id()));
    }

    public LoginResult login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        UserAccount user = users.findByUsername(normalizedUsername)
                .orElseThrow(() -> new IllegalArgumentException("invalid username or password"));
        if (!"active".equals(user.status()) || !verify(password, users.passwordHash(user.id()).orElse(""))) {
            throw new IllegalArgumentException("invalid username or password");
        }
        return new LoginResult(user, createSession(user.id()));
    }

    public UserAccount currentUser(String token) {
        String userId = sessions.findActiveUserId(hashToken(token)).orElseThrow(() -> new IllegalArgumentException("session expired"));
        return users.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) sessions.delete(hashToken(token));
    }

    private String createSession(String userId) {
        String token = randomToken();
        sessions.create(hashToken(token), userId, LocalDateTime.now().plusDays(14));
        return token;
    }

    private String normalizeUsername(String value) {
        String username = value == null ? "" : value.trim().toLowerCase();
        if (!username.matches("[a-z0-9_]{3,32}")) throw new IllegalArgumentException("username must be 3-32 lowercase letters, numbers, or underscores");
        return username;
    }

    private String normalizeDisplayName(String value, String fallback) {
        String name = value == null || value.isBlank() ? fallback : value.trim();
        if (name.length() > 128) throw new IllegalArgumentException("display name is too long");
        return name;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password must contain 8-128 characters");
        }
    }

    private String hash(String password) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    private boolean verify(String password, String stored) {
        try {
            String[] values = stored.split("\\$");
            if (values.length != 3) return false;
            byte[] actual = derive(password, Base64.getDecoder().decode(values[1]), Integer.parseInt(values[0]));
            return MessageDigest.isEqual(actual, Base64.getDecoder().decode(values[2]));
        } catch (Exception ignored) { return false; }
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)).getEncoded();
        } catch (Exception error) { throw new IllegalStateException("cannot hash password", error); }
    }

    private String randomToken() { byte[] value = new byte[32]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String hashToken(String token) { try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException(error); } }

    public record LoginResult(UserAccount user, String sessionToken) { }
}
