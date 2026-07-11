package com.agent.javascope.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentJsonCodecUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public Map<String, Object> parseJson(String text) {
        String normalizedText = extractJsonObject(normalize(text, "{}"));
        try {
            return OBJECT_MAPPER.readValue(normalizedText, MAP_TYPE);
        } catch (JsonProcessingException ignored) {
            return new LinkedHashMap<>();
        }
    }

    public <T> T convert(Object value, Class<T> targetType) {
        return OBJECT_MAPPER.convertValue(value, targetType);
    }

    public JsonNode toTree(Object value) {
        return OBJECT_MAPPER.valueToTree(value);
    }

    public JsonNode parseTree(String text) throws JsonProcessingException {
        return OBJECT_MAPPER.readTree(text);
    }

    public ObjectNode emptyObject() {
        return OBJECT_MAPPER.createObjectNode();
    }

    public <T> List<T> asList(Object value, Class<T> itemType) {
        if (!(value instanceof List<?>)) {
            return new ArrayList<>();
        }
        JavaType listType = OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, itemType);
        return OBJECT_MAPPER.convertValue(value, listType);
    }

    public String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            return "{}";
        }
    }

    public Map<String, Object> asMap(Object value) {
        if (value instanceof JsonNode node) {
            if (!node.isObject()) {
                return new LinkedHashMap<>();
            }
            return OBJECT_MAPPER.convertValue(node, MAP_TYPE);
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return map;
        }
        return new LinkedHashMap<>();
    }

    public List<Map<String, Object>> asPlanList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            result.add(asMap(item));
        }
        return result;
    }

    public boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return defaultValue;
    }

    public List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    public String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }
}
