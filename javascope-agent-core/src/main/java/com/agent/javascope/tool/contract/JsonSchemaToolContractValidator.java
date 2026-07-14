package com.agent.javascope.tool.contract;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Core 内置的轻量 JSON Schema 校验器。
 * 第一版覆盖工具契约最需要的 type/required/properties/items/enum/pattern/format/range/additionalProperties。
 */
public class JsonSchemaToolContractValidator implements ToolContractValidator {

    @Override
    public List<String> validateInput(AgentToolDefinition definition, JsonNode input) {
        return validate(definition == null ? Map.of() : definition.getInputSchema(), input, "input");
    }

    @Override
    public List<String> validateOutput(AgentToolDefinition definition, JsonNode output) {
        return validate(definition == null ? Map.of() : definition.getOutputSchema(), output, "output");
    }

    public List<String> validate(Map<String, Object> schema, JsonNode value, String rootPath) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.isEmpty()) {
            return errors;
        }
        validateNode(schema, value, rootPath == null || rootPath.isBlank() ? "$" : rootPath, errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private void validateNode(Map<String, Object> schema, JsonNode value, String path, List<String> errors) {
        if (!matchesDeclaredType(schema.get("type"), value)) {
            errors.add(path + " 类型不符合契约，期望 " + schema.get("type") + "，实际 " + nodeType(value));
            return;
        }
        if (value == null || value.isNull() || value.isMissingNode()) {
            return;
        }
        validateEnum(schema.get("enum"), value, path, errors);
        validateString(schema, value, path, errors);
        validateNumber(schema, value, path, errors);

        if (value.isObject()) {
            Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> raw
                    ? stringMap(raw)
                    : Map.of();
            if (schema.get("required") instanceof List<?> required) {
                for (Object item : required) {
                    String field = String.valueOf(item);
                    JsonNode child = value.get(field);
                    // JSON Schema 的 required 只约束字段存在；是否可为 null/空串由 type/minLength 决定。
                    if (child == null || child.isMissingNode()) {
                        errors.add(path + "." + field + " 为必填字段");
                    }
                }
            }
            value.fields().forEachRemaining(entry -> {
                Object childSchema = properties.get(entry.getKey());
                if (childSchema instanceof Map<?, ?> map) {
                    validateNode(stringMap(map), entry.getValue(), path + "." + entry.getKey(), errors);
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    errors.add(path + "." + entry.getKey() + " 未在契约中声明");
                }
            });
        }
        if (value.isArray() && schema.get("items") instanceof Map<?, ?> itemSchema) {
            for (int i = 0; i < value.size(); i++) {
                validateNode(stringMap(itemSchema), value.get(i), path + "[" + i + "]", errors);
            }
        }
    }

    private boolean matchesDeclaredType(Object declaredType, JsonNode value) {
        if (declaredType == null) return true;
        if (declaredType instanceof List<?> types) {
            return types.stream().anyMatch(type -> matchesType(String.valueOf(type), value));
        }
        return matchesType(String.valueOf(declaredType), value);
    }

    private boolean matchesType(String type, JsonNode value) {
        if ("null".equals(type)) return value == null || value.isNull();
        if (value == null || value.isNull()) return false;
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "any", "" -> true;
            default -> true;
        };
    }

    private void validateEnum(Object enumSchema, JsonNode value, String path, List<String> errors) {
        if (!(enumSchema instanceof List<?> allowed) || value == null || value.isContainerNode()) return;
        String actual = value.isTextual() ? value.asText() : value.toString();
        if (allowed.stream().noneMatch(item -> Objects.equals(String.valueOf(item), actual))) {
            errors.add(path + " 不在允许枚举中: " + allowed);
        }
    }

    private void validateString(Map<String, Object> schema, JsonNode value, String path, List<String> errors) {
        if (value == null || !value.isTextual()) return;
        String text = value.asText();
        if (schema.get("pattern") instanceof String pattern && !pattern.isBlank()) {
            try {
                if (!Pattern.compile(pattern).matcher(text).matches()) {
                    errors.add(path + " 不符合 pattern=" + pattern);
                }
            } catch (PatternSyntaxException e) {
                errors.add(path + " 的 Schema pattern 非法");
            }
        }
        if ("date".equals(schema.get("format"))) {
            try {
                LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                errors.add(path + " 必须是 yyyy-MM-dd 日期");
            }
        }
        if (schema.get("minLength") instanceof Number min && text.length() < min.intValue()) {
            errors.add(path + " 长度不能小于 " + min.intValue());
        }
    }

    private void validateNumber(Map<String, Object> schema, JsonNode value, String path, List<String> errors) {
        if (value == null || !value.isNumber()) return;
        double actual = value.doubleValue();
        if (schema.get("minimum") instanceof Number minimum && actual < minimum.doubleValue()) {
            errors.add(path + " 不能小于 " + minimum);
        }
        if (schema.get("exclusiveMinimum") instanceof Number minimum && actual <= minimum.doubleValue()) {
            errors.add(path + " 必须大于 " + minimum);
        }
        if (schema.get("maximum") instanceof Number maximum && actual > maximum.doubleValue()) {
            errors.add(path + " 不能大于 " + maximum);
        }
    }

    private Map<String, Object> stringMap(Map<?, ?> raw) {
        java.util.LinkedHashMap<String, Object> mapped = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> mapped.put(String.valueOf(key), value));
        return mapped;
    }

    private String nodeType(JsonNode value) {
        return value == null ? "null" : value.getNodeType().name().toLowerCase();
    }
}
