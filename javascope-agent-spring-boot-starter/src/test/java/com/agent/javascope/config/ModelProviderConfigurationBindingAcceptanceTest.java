package com.agent.javascope.config;

import com.agent.javascope.runtime.AgentRuntimeProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/** 验证 Spring Boot 可将 provider 专属配置绑定到框架属性。 */
public final class ModelProviderConfigurationBindingAcceptanceTest {

    public static void main(String[] args) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "java.agent-runtime.provider", "kimi",
                "java.agent-runtime.kimi.model", "kimi-k3",
                "java.agent-runtime.kimi.api-key", "moonshot-key",
                "java.agent-runtime.kimi.base-url", "https://api.moonshot.cn/v1",
                "java.agent-runtime.kimi.temperature-enabled", "false",
                "java.agent-runtime.deepseek.api-key", "deepseek-key",
                "java.agent-runtime.model-max-retries", "3",
                "java.agent-runtime.model-retry-base-delay-ms", "500",
                "java.agent-runtime.model-retry-max-delay-ms", "5000"));

        AgentRuntimeProperties properties = new Binder(source)
                .bind("java.agent-runtime", Bindable.of(AgentRuntimeProperties.class))
                .orElseThrow(() -> new AssertionError("agent runtime properties were not bound"));

        assertEquals("kimi", properties.getProvider());
        assertEquals("kimi-k3", properties.getModel());
        assertEquals("moonshot-key", properties.getApiKey());
        assertEquals("https://api.moonshot.cn/v1", properties.getBaseUrl());
        if (properties.isTemperatureEnabled()) {
            throw new AssertionError("Kimi K3 must not send temperature");
        }
        assertEquals(3, properties.getModelMaxRetries());
        assertEquals(500L, properties.getModelRetryBaseDelayMs());
        assertEquals(5000L, properties.getModelRetryMaxDelayMs());

        properties.setProvider("deepseek");
        assertEquals("deepseek-key", properties.getApiKey());
        assertEquals("deepseek-chat", properties.getModel());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
