package com.stockmind.bootstrap.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class UserMemoryService {

    private static final Set<String> SUPPORTED_KEYS = Set.of(
            "language",
            "name",
            "tone",
            "timezone",
            "business_preferences");
    private static final Pattern EXPLICIT_MEMORY_PATTERN = Pattern.compile("^([a-zA-Z_]+)\\s*[:=：]\\s*(.+)$");

    private final UserMemoryRepository repository;
    private final ObjectMapper objectMapper;

    public UserMemoryService(UserMemoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Set<String> supportedKeys() {
        return SUPPORTED_KEYS;
    }

    public boolean isSupportedKey(String key) {
        return key != null && SUPPORTED_KEYS.contains(key);
    }

    public void putExplicitMemory(String userId, String key, Object value, Long ttlSeconds) {
        if (!isSupportedKey(key)) {
            throw new IllegalArgumentException("不支持的 key: " + key);
        }
        LocalDateTime expireAt = ttlSeconds == null || ttlSeconds <= 0
                ? null
                : LocalDateTime.now().plusSeconds(ttlSeconds);
        repository.upsert(userId, key, value, "global", "explicit", expireAt);
    }

    public List<UserMemoryEntry> list(String userId) {
        return repository.listByUser(userId);
    }

    public void delete(String userId, String key) {
        repository.deleteByUserAndKey(userId, key);
    }

    public String buildMemoryPrompt(String userId) {
        List<UserMemoryEntry> entries = repository.listByUserAndKeys(userId, new ArrayList<>(SUPPORTED_KEYS));
        if (entries.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (UserMemoryEntry entry : entries) {
            String text = renderValue(entry.value());
            if (text.isBlank()) {
                continue;
            }
            lines.add(entry.key() + "=" + text);
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "以下是用户长期记忆，请优先遵循：\n" + String.join("\n", lines) + "\n";
    }

    public Optional<String> tryWriteByInputCommand(String userId, String input) {
        String normalized = input == null ? "" : input.trim();
        if (!normalized.startsWith("记住")) {
            return Optional.empty();
        }
        String body = normalized.substring(2).trim();
        Matcher matcher = EXPLICIT_MEMORY_PATTERN.matcher(body);
        if (!matcher.matches()) {
            return Optional.of("记忆写入格式错误，请使用：记住 key=value。支持 key: " + String.join(",", SUPPORTED_KEYS));
        }
        String key = matcher.group(1).trim();
        String value = matcher.group(2).trim();
        if (!isSupportedKey(key)) {
            return Optional.of("不支持的 key: " + key + "。支持 key: " + String.join(",", SUPPORTED_KEYS));
        }
        putExplicitMemory(userId, key, value, null);
        return Optional.of("已记住：" + key + "=" + value);
    }

    private String renderValue(String jsonValue) {
        try {
            Object value = objectMapper.readValue(jsonValue, Object.class);
            if (value instanceof String s) {
                return s;
            }
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                return objectMapper.writeValueAsString(value);
            }
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return jsonValue == null ? "" : jsonValue;
        }
    }
}
