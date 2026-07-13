package com.agent.javascope.user.memory;

import com.agent.javascope.user.infrastructure.JdbcUserMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class UserMemoryService {

    public static final String DEFAULT_NAMESPACE = "assistant";
    private static final Set<String> GLOBAL_KEYS = Set.of("language", "name", "tone", "timezone");
    private static final Set<String> BUSINESS_KEYS = Set.of("business_preferences");
    private static final Pattern COMMAND = Pattern.compile("^([a-zA-Z_]+)\\s*[:=：]\\s*(.+)$");

    private final JdbcUserMemoryRepository repository;
    private final ObjectMapper objectMapper;

    public UserMemoryService(JdbcUserMemoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public boolean isSupportedKey(String key) {
        return key != null && (GLOBAL_KEYS.contains(key) || BUSINESS_KEYS.contains(key));
    }

    public void put(String userId, String businessCode, String key, Object value, Long ttlSeconds) {
        if (!isSupportedKey(key)) {
            throw new IllegalArgumentException("unsupported memory key: " + key);
        }
        if (value == null) {
            throw new IllegalArgumentException("memory value must not be null");
        }
        boolean global = GLOBAL_KEYS.contains(key);
        LocalDateTime expiresAt = ttlSeconds == null || ttlSeconds <= 0
                ? null : LocalDateTime.now().plusSeconds(ttlSeconds);
        repository.upsert(new UserMemory(
                userId, DEFAULT_NAMESPACE, global ? "global" : "business",
                global ? "" : normalizeBusiness(businessCode), key, toJson(value),
                "explicit", LocalDateTime.now(), expiresAt));
    }

    public List<UserMemory> list(String userId) {
        return repository.list(userId);
    }

    public void delete(String userId, String businessCode, String key) {
        boolean global = GLOBAL_KEYS.contains(key);
        repository.delete(userId, DEFAULT_NAMESPACE, global ? "global" : "business",
                global ? "" : normalizeBusiness(businessCode), key);
    }

    public MemoryContext loadContext(String userId, String businessCode) {
        LinkedHashMap<String, Object> global = new LinkedHashMap<>();
        LinkedHashMap<String, Object> business = new LinkedHashMap<>();
        for (UserMemory item : repository.listForContext(userId, DEFAULT_NAMESPACE, businessCode)) {
            ("global".equals(item.scopeType()) ? global : business).put(item.key(), fromJson(item.value()));
        }
        return new MemoryContext(Map.copyOf(global), Map.copyOf(business));
    }

    public Optional<String> tryWriteCommand(String userId, String businessCode, String input) {
        String value = input == null ? "" : input.trim();
        if (!value.startsWith("记住")) {
            return Optional.empty();
        }
        Matcher matcher = COMMAND.matcher(value.substring(2).trim());
        if (!matcher.matches()) {
            return Optional.of("记忆写入格式错误，请使用：记住 key=value");
        }
        String key = matcher.group(1).trim();
        if (!isSupportedKey(key)) {
            return Optional.of("不支持的记忆 key: " + key);
        }
        String memoryValue = matcher.group(2).trim();
        put(userId, businessCode, key, memoryValue, null);
        return Optional.of("已记住：" + key + "=" + memoryValue);
    }

    private String normalizeBusiness(String value) {
        return value == null || value.isBlank() ? "general" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("memory value cannot be serialized", e);
        }
    }

    private Object fromJson(String value) {
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception e) {
            return value;
        }
    }

    public record MemoryContext(Map<String, Object> global, Map<String, Object> business) {
    }
}
