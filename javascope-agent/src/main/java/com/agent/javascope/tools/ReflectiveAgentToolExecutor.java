package com.agent.javascope.tools;

import com.agent.javascope.spi.AgentTool;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.SmartInitializingSingleton;

public class ReflectiveAgentToolExecutor implements AgentToolExecutor, SmartInitializingSingleton {

    private final Map<String, ToolMethod> registry = new LinkedHashMap<>();
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
        List<Map<String, Object>> schemas = new java.util.ArrayList<>();
        for (Map.Entry<String, ToolMethod> entry : registry.entrySet()) {
            String name = entry.getKey();
            Method method = entry.getValue().method();
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            schemas.add(Map.of(
                    "name", name,
                    "description", annotation == null ? "" : annotation.description(),
                    "input_schema", Map.of("type", "object", "properties", Map.of(), "required", List.of())));
        }
        return schemas;
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
