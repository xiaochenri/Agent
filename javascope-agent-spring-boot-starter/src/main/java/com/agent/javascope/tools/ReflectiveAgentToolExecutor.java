package com.agent.javascope.tools;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.tool.error.DefaultToolErrorClassifier;
import com.agent.javascope.tool.middleware.ToolResultFactory;
import com.agent.javascope.tool.runtime.ToolError;
import com.agent.javascope.tool.runtime.ToolErrorCode;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolExecutionStatus;
import com.agent.javascope.tool.runtime.ToolInvocation;
import com.agent.javascope.tool.registry.ToolRegistry;
import com.agent.javascope.tool.invocation.ToolInvoker;
import com.agent.javascope.tool.annotation.AgentTool;
import com.agent.javascope.tool.annotation.ToolField;
import com.agent.javascope.tool.annotation.ToolVisibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** Spring 反射适配器：提供工具注册表和底层反射调用能力。 */
public class ReflectiveAgentToolExecutor implements ToolRegistry, ToolInvoker, SmartInitializingSingleton {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ReflectiveAgentToolExecutor.class);

    private final Map<String, ToolMethod> registry = new LinkedHashMap<>();
    private final Map<String, AgentToolDefinition> definitions = new LinkedHashMap<>();
    private final ApplicationContext applicationContext;
    private boolean initialized;

    public ReflectiveAgentToolExecutor(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        LOG.debug("Initializing reflective agent tool registry after Spring singleton creation");
        ensureInitialized();
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            LOG.debug("Reflective agent tool registry is already initialized, registeredToolCount={}", registry.size());
            return;
        }
        /*
         * 扫描所有 Spring Bean 方法上的 @AgentTool。registry 负责实际反射调用，
         * definitions 负责 prompt 暴露、入参校验和执行策略判断。
         */
        Map<String, Object> beans = applicationContext.getBeansOfType(Object.class);
        LOG.debug("Scanning Spring beans for @AgentTool methods, beanCount={}", beans.size());
        for (Object bean : beans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            if (targetClass == null) {
                LOG.debug("Skipping bean without a resolvable target class, beanType={}", bean.getClass().getName());
                continue;
            }
            for (Method method : targetClass.getDeclaredMethods()) {
                AgentTool annotation = method.getAnnotation(AgentTool.class);
                if (annotation == null) {
                    continue;
                }
                method.setAccessible(true);
                if (registry.containsKey(annotation.name())) {
                    LOG.warn("Replacing duplicate @AgentTool registration, tool={}, previousMethod={}, newMethod={}",
                            annotation.name(), registry.get(annotation.name()).method().toGenericString(), method.toGenericString());
                }
                registry.put(annotation.name(), new ToolMethod(bean, method));
                definitions.put(annotation.name(), buildDefinition(annotation, method));
                LOG.debug("Registered reflective agent tool, tool={}, targetClass={}, method={}, parameterCount={}",
                        annotation.name(), targetClass.getName(), method.getName(), method.getParameterCount());
            }
        }
        initialized = true;
        LOG.info("Reflective agent tool registry initialized, registeredToolCount={}", registry.size());
    }

    @Override
    public ToolExecutionResult invoke(AgentToolDefinition ignored, ToolInvocation invocation) {
        ensureInitialized();
        String tool = invocation == null ? "" : invocation.toolName();
        Map<String, Object> input = invocation == null
                ? Map.of()
                : OBJECT_MAPPER.convertValue(invocation.input(), new TypeReference<Map<String, Object>>() {});
        String rawInput = invocation == null ? "" : invocation.rawInput();
        ToolMethod toolMethod = registry.get(tool);
        LOG.debug("Invoking reflective agent tool, tool={}, inputFieldCount={}, hasRawInput={}",
                tool, input.size(), !rawInput.isBlank());
        if (toolMethod == null) {
            LOG.debug("Reflective agent tool invocation rejected because the tool is not registered, tool={}", tool);
            return buildFail(tool, ToolErrorCode.TOOL_NOT_REGISTERED, "工具未注册", false);
        }
        AgentToolDefinition definition = definitions.get(tool);
        if (definition == null || definition.getVisibility() == ToolVisibility.DISABLED) {
            LOG.debug("Reflective agent tool invocation rejected because the tool is disabled, tool={}", tool);
            return buildFail(tool, ToolErrorCode.TOOL_DISABLED, "工具当前已禁用", false);
        }
        // 执行前先按工具协议做最小校验，避免明显非法参数进入业务方法。
        List<String> validationErrors = validateInput(definition, input);
        if (!validationErrors.isEmpty()) {
            LOG.debug("Reflective agent tool input validation failed, tool={}, errorCount={}", tool, validationErrors.size());
            return buildFail(tool, ToolErrorCode.TOOL_INPUT_CONTRACT_VIOLATION,
                    String.join("; ", validationErrors), false);
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
            ToolExecutionResult executionResult = result == null
                    ? buildFail(tool, ToolErrorCode.TOOL_RESULT_NULL, "工具未返回结果", false)
                    : toToolResult(tool, result);
            LOG.debug("Reflective agent tool invocation completed, tool={}, status={}, retryable={}",
                    tool, executionResult.status(), executionResult.retryable());
            return executionResult;
        } catch (Exception e) {
            LOG.warn("Reflective agent tool invocation failed, tool={}, exceptionType={}", tool, e.getClass().getSimpleName());
            LOG.debug("Reflective agent tool invocation failure details, tool={}", tool, e);
            return ToolResultFactory.failed(tool, DefaultToolErrorClassifier.INSTANCE.classify(e));
        }
    }

    @Override
    public List<Map<String, Object>> listModelVisibleSchemas() {
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
        LOG.debug("Listed model-visible agent tool schemas, schemaCount={}", schemas.size());
        return schemas;
    }

    @Override
    public List<AgentToolDefinition> listDefinitions() {
        ensureInitialized();
        List<AgentToolDefinition> result = new ArrayList<>(definitions.values());
        LOG.debug("Listed agent tool definitions, definitionCount={}", result.size());
        return result;
    }

    @Override
    public AgentToolDefinition findDefinition(String name) {
        ensureInitialized();
        AgentToolDefinition definition = definitions.get(name);
        LOG.debug("Looked up agent tool definition, tool={}, found={}", name, definition != null);
        return definition;
    }

    private AgentToolDefinition buildDefinition(AgentTool annotation, Method method) {
        LOG.debug("Building agent tool definition, tool={}, method={}", annotation.name(), method.toGenericString());
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
        definition.setInputSchema(resolveSchema(
                annotation.name(), "input_schema", annotation.inputSchema(), inferInputSchema(method)));
        definition.setOutputSchema(resolveSchema(
                annotation.name(), "output_schema", annotation.outputSchema(), defaultOutputSchema()));
        definition.setStrictOutputContract(annotation.outputSchema() != null && !annotation.outputSchema().isBlank());
        definition.setExamples(parseExamples(annotation.examples()));
        return definition;
    }

    private Map<String, Object> resolveSchema(
            String toolName, String schemaType, String schemaJson, Map<String, Object> fallback) {
        if (schemaJson == null || schemaJson.isBlank()) {
            LOG.debug("Using inferred schema because no explicit tool schema was supplied");
            return fallback;
        }
        try {
            return OBJECT_MAPPER.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            // 显式契约写错时必须启动失败，不能把 Map 入参静默降级成无 required 字段的空 Schema。
            throw new IllegalStateException(
                    "Invalid explicit " + schemaType + " for tool " + toolName + ": "
                            + e.getOriginalMessage(),
                    e);
        }
    }

    private List<Map<String, Object>> parseExamples(String[] examples) {
        List<Map<String, Object>> parsed = new ArrayList<>();
        if (examples == null) {
            LOG.debug("No agent tool examples configured");
            return parsed;
        }
        for (String example : examples) {
            if (example == null || example.isBlank()) {
                continue;
            }
            try {
                parsed.add(OBJECT_MAPPER.readValue(example, new TypeReference<Map<String, Object>>() {}));
            } catch (JsonProcessingException e) {
                LOG.debug("Agent tool example is not JSON; retaining it as a description");
                parsed.add(Map.of("description", example));
            }
        }
        LOG.debug("Parsed agent tool examples, exampleCount={}", parsed.size());
        return parsed;
    }

    private Map<String, Object> inferInputSchema(Method method) {
        if (method.getParameterCount() == 0) {
            LOG.debug("Inferred empty input schema for no-argument tool method, method={}", method.toGenericString());
            return objectSchema(Map.of(), List.of());
        }
        Class<?> inputType = method.getParameterTypes()[0];
        // Map 入参无法可靠推断字段，业务工具应优先在 @AgentTool.inputSchema 中声明。
        if (Map.class.isAssignableFrom(inputType)) {
            LOG.debug("Cannot infer fields for Map input; using empty object schema, method={}", method.toGenericString());
            return objectSchema(Map.of(), List.of());
        }
        LOG.debug("Inferring input schema from tool input type, method={}, inputType={}", method.toGenericString(), inputType.getName());
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
        Map<String, Object> schema = objectSchema(properties, required);
        LOG.debug("Generated input schema from class fields, type={}, propertyCount={}, requiredCount={}",
                type.getName(), properties.size(), required.size());
        return schema;
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
        properties.put("error", Map.of(
                "type", "object",
                "properties", Map.of(
                        "category", Map.of("type", "string"),
                        "code", Map.of("type", "string"),
                        "public_message", Map.of("type", "string"),
                        "recovery_owner", Map.of("type", "string"),
                        "allowed_actions", Map.of("type", "array", "items", Map.of("type", "string")),
                        "retryable", Map.of("type", "boolean"))));
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

    private ToolExecutionResult toToolResult(String fallbackToolName, Object result) {
        if (result instanceof ToolExecutionResult typed) {
            LOG.debug("Using typed tool execution result, tool={}, status={}", fallbackToolName, typed.status());
            return typed;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(String.valueOf(result));
            if (node == null || !node.isObject()) {
                return buildFail(fallbackToolName, ToolErrorCode.TOOL_RESULT_INVALID_JSON,
                        "工具返回结果不是 JSON 对象", false);
            }
            String toolName = node.path("tool").asText(fallbackToolName);
            ToolExecutionStatus status = "success".equals(node.path("status").asText())
                    ? ToolExecutionStatus.SUCCESS
                    : ToolExecutionStatus.FAILED;
            List<String> validationErrors = OBJECT_MAPPER.convertValue(
                    node.path("validation_errors"), new TypeReference<List<String>>() {});
            ToolError toolError = null;
            if (status == ToolExecutionStatus.FAILED) {
                String publicMessage = validationErrors.stream().findFirst().orElse("工具执行失败");
                toolError = DefaultToolErrorClassifier.INSTANCE.classify(
                        node.path("error_code").asText(ToolErrorCode.TOOL_EXECUTION_FAILED.code()),
                        publicMessage,
                        node.path("retryable").asBoolean(false));
            }
            return new ToolExecutionResult(
                    toolName,
                    status,
                    node.path("validation_passed").asBoolean(status == ToolExecutionStatus.SUCCESS),
                    OBJECT_MAPPER.convertValue(node.path("validation_rules"), new TypeReference<List<String>>() {}),
                    validationErrors,
                    node.path("retryable").asBoolean(false),
                    node.path("error_code").asText(""),
                    node.has("data") ? node.get("data") : NullNode.getInstance(),
                    node.has("metadata") ? node.get("metadata") : OBJECT_MAPPER.createObjectNode(),
                    toolError);
        } catch (Exception e) {
            LOG.debug("Unable to convert reflective tool result to typed result, tool={}, exceptionType={}",
                    fallbackToolName, e.getClass().getSimpleName());
            return buildFail(fallbackToolName, ToolErrorCode.TOOL_RESULT_INVALID_JSON,
                    "工具返回结果不是有效 JSON", false);
        }
    }

    /** 将反射适配器自身发现的协议错误转换为统一失败结果。 */
    private ToolExecutionResult buildFail(
            String toolName, ToolErrorCode code, String message, boolean retryable) {
        LOG.debug("Building failed tool execution result, tool={}, code={}, retryable={}",
                toolName, code, retryable);
        return ToolResultFactory.failed(toolName, code, message, retryable);
    }

    private record ToolMethod(Object bean, Method method) {}
}
