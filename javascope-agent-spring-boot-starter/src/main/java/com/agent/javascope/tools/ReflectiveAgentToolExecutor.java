package com.agent.javascope.tools;

import com.agent.javascope.entity.AgentToolDefinition;
import com.agent.javascope.spi.AgentTool;
import com.agent.javascope.spi.ToolField;
import com.agent.javascope.spi.ToolVisibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.SmartInitializingSingleton;

public class ReflectiveAgentToolExecutor implements AgentToolExecutor, SmartInitializingSingleton {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, ToolMethod> registry = new LinkedHashMap<>();
    private final Map<String, AgentToolDefinition> definitions = new LinkedHashMap<>();
    private final ApplicationContext applicationContext;
    private boolean initialized;

    public ReflectiveAgentToolExecutor(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ensureInitialized();
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        /*
         * 扫描所有 Spring Bean 方法上的 @AgentTool。registry 负责实际反射调用，
         * definitions 负责 prompt 暴露、入参校验和执行策略判断。
         */
        Map<String, Object> beans = applicationContext.getBeansOfType(Object.class);
        for (Object bean : beans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            if (targetClass == null) {
                continue;
            }
            for (Method method : targetClass.getDeclaredMethods()) {
                AgentTool annotation = method.getAnnotation(AgentTool.class);
                if (annotation == null) {
                    continue;
                }
                method.setAccessible(true);
                registry.put(annotation.name(), new ToolMethod(bean, method));
                definitions.put(annotation.name(), buildDefinition(annotation, method));
            }
        }
        initialized = true;
    }

    @Override
    public String execute(String tool, Map<String, Object> input, String rawInput) {
        ensureInitialized();
        ToolMethod toolMethod = registry.get(tool);
        if (toolMethod == null) {
            return buildFail(tool, "tool not registered", false);
        }
        AgentToolDefinition definition = definitions.get(tool);
        if (definition == null || definition.getVisibility() == ToolVisibility.DISABLED) {
            return buildFail(tool, "tool disabled", false);
        }
        // 执行前先按工具协议做最小校验，避免明显非法参数进入业务方法。
        List<String> validationErrors = validateInput(definition, input);
        if (!validationErrors.isEmpty()) {
            return buildFail(tool, String.join("; ", validationErrors), true);
        }
        try {
            Method method = toolMethod.method();
            Object result;
            if (method.getParameterCount() == 2) {
                result = method.invoke(toolMethod.bean(), input, rawInput);
            } else if (method.getParameterCount() == 1) {
                result = method.invoke(toolMethod.bean(), input);
            } else {
                result = method.invoke(toolMethod.bean());
            }
            return result == null ? buildFail(tool, "tool result is null", false) : String.valueOf(result);
        } catch (Exception e) {
            return buildFail(tool, "tool execute exception: " + e.getClass().getSimpleName(), true);
        }
    }

    @Override
    public List<Map<String, Object>> listToolSchemas() {
        ensureInitialized();
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (AgentToolDefinition definition : definitions.values()) {
            // prompt 只暴露模型可见或模型内部工具，runtime-only 工具不进入模型上下文。
            if (definition.getVisibility() == ToolVisibility.RUNTIME_INTERNAL
                    || definition.getVisibility() == ToolVisibility.DISABLED) {
                continue;
            }
            schemas.add(OBJECT_MAPPER.convertValue(definition, new TypeReference<Map<String, Object>>() {}));
        }
        return schemas;
    }

    @Override
    public List<AgentToolDefinition> listToolDefinitions() {
        ensureInitialized();
        return new ArrayList<>(definitions.values());
    }

    @Override
    public AgentToolDefinition getToolDefinition(String name) {
        ensureInitialized();
        return definitions.get(name);
    }

    private AgentToolDefinition buildDefinition(AgentTool annotation, Method method) {
        // 注解显式 schema 优先；未配置时从强类型入参尽力推断。
        AgentToolDefinition definition = new AgentToolDefinition();
        definition.setName(annotation.name());
        definition.setTitle(firstNonBlank(annotation.title(), annotation.name()));
        definition.setDescription(annotation.description());
        definition.setNamespace(annotation.namespace());
        definition.setCategory(annotation.category());
        definition.setVersion(annotation.version());
        definition.setTags(Arrays.asList(annotation.tags()));
        definition.setToolType(annotation.toolType());
        definition.setVisibility(annotation.visibility());
        definition.setDangerLevel(annotation.dangerLevel());
        definition.setReadOnly(annotation.readOnly());
        definition.setIdempotent(annotation.idempotent());
        definition.setRequiresConfirmation(annotation.requiresConfirmation());
        definition.setAllowedDirectCall(annotation.allowedDirectCall());
        definition.setAllowedInPlanStep(annotation.allowedInPlanStep());
        definition.setTimeoutMs(annotation.timeoutMs());
        definition.setInputSchema(resolveSchema(annotation.inputSchema(), inferInputSchema(method)));
        definition.setOutputSchema(resolveSchema(annotation.outputSchema(), defaultOutputSchema()));
        definition.setExamples(parseExamples(annotation.examples()));
        return definition;
    }

    private Map<String, Object> resolveSchema(String schemaJson, Map<String, Object> fallback) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return fallback;
        }
        try {
            return OBJECT_MAPPER.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            Map<String, Object> invalid = new LinkedHashMap<>(fallback);
            invalid.put("schema_parse_error", e.getOriginalMessage());
            return invalid;
        }
    }

    private List<Map<String, Object>> parseExamples(String[] examples) {
        List<Map<String, Object>> parsed = new ArrayList<>();
        if (examples == null) {
            return parsed;
        }
        for (String example : examples) {
            if (example == null || example.isBlank()) {
                continue;
            }
            try {
                parsed.add(OBJECT_MAPPER.readValue(example, new TypeReference<Map<String, Object>>() {}));
            } catch (JsonProcessingException e) {
                parsed.add(Map.of("description", example));
            }
        }
        return parsed;
    }

    private Map<String, Object> inferInputSchema(Method method) {
        if (method.getParameterCount() == 0) {
            return objectSchema(Map.of(), List.of());
        }
        Class<?> inputType = method.getParameterTypes()[0];
        // Map 入参无法可靠推断字段，业务工具应优先在 @AgentTool.inputSchema 中声明。
        if (Map.class.isAssignableFrom(inputType)) {
            return objectSchema(Map.of(), List.of());
        }
        return schemaForClass(inputType);
    }

    private Map<String, Object> schemaForClass(Class<?> type) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                ToolField field = component.getAnnotation(ToolField.class);
                properties.put(component.getName(), schemaForType(component.getGenericType(), field));
                if (field != null && field.required()) {
                    required.add(component.getName());
                }
            }
            return objectSchema(properties, required);
        }
        for (Field declaredField : type.getDeclaredFields()) {
            ToolField field = declaredField.getAnnotation(ToolField.class);
            properties.put(declaredField.getName(), schemaForType(declaredField.getGenericType(), field));
            if (field != null && field.required()) {
                required.add(declaredField.getName());
            }
        }
        return objectSchema(properties, required);
    }

    private Map<String, Object> schemaForType(Type type, ToolField field) {
        Map<String, Object> schema = new LinkedHashMap<>();
        String typeName = type.getTypeName();
        if (typeName.equals(String.class.getName())) {
            schema.put("type", "string");
        } else if (typeName.equals(Integer.class.getName()) || typeName.equals(int.class.getName())
                || typeName.equals(Long.class.getName()) || typeName.equals(long.class.getName())
                || typeName.equals(BigInteger.class.getName())) {
            schema.put("type", "integer");
        } else if (typeName.equals(Double.class.getName()) || typeName.equals(double.class.getName())
                || typeName.equals(Float.class.getName()) || typeName.equals(float.class.getName())
                || typeName.equals(BigDecimal.class.getName())) {
            schema.put("type", "number");
        } else if (typeName.equals(Boolean.class.getName()) || typeName.equals(boolean.class.getName())) {
            schema.put("type", "boolean");
        } else if (typeName.startsWith(List.class.getName()) || typeName.startsWith("java.util.List")) {
            schema.put("type", "array");
            schema.put("items", Map.of("type", "object"));
        } else if (typeName.startsWith(Map.class.getName()) || typeName.startsWith("java.util.Map")) {
            schema.put("type", "object");
        } else {
            schema.put("type", "object");
        }
        if (field != null) {
            if (!field.description().isBlank()) {
                schema.put("description", field.description());
            }
            if (field.enums().length > 0) {
                schema.put("enum", Arrays.asList(field.enums()));
            }
            if (!field.defaultValue().isBlank()) {
                schema.put("default", field.defaultValue());
            }
        }
        return schema;
    }

    private Map<String, Object> defaultOutputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tool", Map.of("type", "string"));
        properties.put("status", Map.of("type", "string", "enum", List.of("success", "failed")));
        properties.put("validation_passed", Map.of("type", "boolean"));
        properties.put("validation_rules", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("validation_errors", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("retryable", Map.of("type", "boolean"));
        properties.put("error_code", Map.of("type", "string"));
        properties.put("data", Map.of("type", "object"));
        properties.put("metadata", Map.of("type", "object"));
        return objectSchema(properties, List.of("tool", "status", "validation_passed", "data"));
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties == null ? Map.of() : properties);
        schema.put("required", required == null ? List.of() : required);
        return schema;
    }

    private List<String> validateInput(AgentToolDefinition definition, Map<String, Object> input) {
        Map<String, Object> schema = definition.getInputSchema();
        List<String> errors = new ArrayList<>();
        // 当前先覆盖 required/type/enum 三类高价值校验，后续可扩展 format/range/pattern。
        Object requiredObj = schema.get("required");
        if (requiredObj instanceof List<?> required) {
            for (Object item : required) {
                String fieldName = String.valueOf(item);
                if (input == null || !input.containsKey(fieldName) || input.get(fieldName) == null
                        || (input.get(fieldName) instanceof String text && text.isBlank())) {
                    errors.add("missing required input: " + fieldName);
                }
            }
        }
        Object propertiesObj = schema.get("properties");
        if (!(propertiesObj instanceof Map<?, ?> properties) || input == null) {
            return errors;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object fieldSchemaObj = properties.get(entry.getKey());
            if (!(fieldSchemaObj instanceof Map<?, ?> fieldSchema)) {
                continue;
            }
            Object value = entry.getValue();
            Object typeObj = fieldSchema.get("type");
            if (typeObj instanceof String type && !matchesType(type, value)) {
                errors.add("input type mismatch: " + entry.getKey() + " expected " + type);
            }
            Object enumObj = fieldSchema.get("enum");
            if (enumObj instanceof List<?> enumValues && value != null
                    && enumValues.stream().noneMatch(allowed -> Objects.equals(String.valueOf(allowed), String.valueOf(value)))) {
                errors.add("input enum mismatch: " + entry.getKey());
            }
        }
        return errors;
    }

    private boolean matchesType(String type, Object value) {
        if (value == null) {
            return true;
        }
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String buildFail(String toolName, String error, boolean retryable) {
        return "{\"tool\":\"" + safe(toolName) + "\",\"status\":\"failed\",\"validation_passed\":false,"
                + "\"validation_rules\":[],\"validation_errors\":[\"" + safe(error) + "\"],\"retryable\":" + retryable + ",\"data\":null}";
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record ToolMethod(Object bean, Method method) {}
}
