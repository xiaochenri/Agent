package com.agent.javascope.user.application;

import com.agent.javascope.user.identity.UserAccount;
import com.agent.javascope.user.infrastructure.JdbcUserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserApplicationService {

    private final JdbcUserRepository repository;

    public UserApplicationService(JdbcUserRepository repository) {
        this.repository = repository;
    }

    public UserAccount getOrCreate(String userId) {
        validate(userId);
        repository.ensureExists(userId);
        return repository.findById(userId).orElseThrow();
    }

    public UserAccount updateProfile(String userId, String displayName, String avatarUrl) {
        validate(userId);
        String normalizedName = normalize(displayName, 128);
        String normalizedAvatar = normalize(avatarUrl, 512);
        return repository.updateProfile(userId, normalizedName, normalizedAvatar);
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("profile field is too long");
        }
        return normalized;
    }

    private void validate(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 64) {
            throw new IllegalArgumentException("userId must contain 1-64 characters");
        }
    }
}
