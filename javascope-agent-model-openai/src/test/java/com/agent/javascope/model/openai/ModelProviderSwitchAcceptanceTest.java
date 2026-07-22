package com.agent.javascope.model.openai;

import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.model.ModelRequest;
import com.agent.javascope.model.ModelResult;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 不依赖外网，验证 provider 切换与向后兼容配置。 */
public final class ModelProviderSwitchAcceptanceTest {

    public static void main(String[] args) throws Exception {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getDeepseek().setApiKey("deepseek-key");
        properties.getKimi().setApiKey("kimi-key");

        properties.setProvider("deepseek");
        assertEquals("deepseek-chat", properties.getModel());
        assertEquals("https://api.deepseek.com/v1", properties.getBaseUrl());
        assertEquals("deepseek-key", properties.getApiKey());
        assertTrue(properties.isTemperatureEnabled());
        OpenAiCompatibleAgentChatModelClient client =
                new OpenAiCompatibleAgentChatModelClient(properties, new AgentJsonCodecUtil());
        Map<String, Object> deepseekBody = client.createRequestBody("system", "user", false);
        assertEquals(0.2d, deepseekBody.get("temperature"));

        properties.setProvider("kimi");
        assertEquals("kimi-k3", properties.getModel());
        assertEquals("https://api.moonshot.cn/v1", properties.getBaseUrl());
        assertEquals("kimi-key", properties.getApiKey());
        assertFalse(properties.isTemperatureEnabled());
        Map<String, Object> kimiBody = client.createRequestBody("system", "user", true);
        assertEquals("kimi-k3", kimiBody.get("model"));
        assertFalse(kimiBody.containsKey("temperature"));
        assertEquals(true, kimiBody.get("stream"));

        properties.setProvider("moonshot");
        assertEquals("kimi-k3", properties.getModel());

        assertOverloadedRequestIsRetried(properties);

        properties.setModel("deployment-alias");
        properties.setApiKey("legacy-key");
        properties.setBaseUrl("https://gateway.example/v1/");
        assertEquals("deployment-alias", properties.getModel());
        assertEquals("legacy-key", properties.getApiKey());
        assertEquals("https://gateway.example/v1/", properties.getBaseUrl());
    }

    private static void assertOverloadedRequestIsRetried(AgentRuntimeProperties properties) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int attempt = attempts.incrementAndGet();
            String response = attempt < 3
                    ? "{\"error\":{\"message\":\"overloaded\",\"type\":\"engine_overloaded_error\"}}"
                    : "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}";
            int status = attempt < 3 ? 429 : 200;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            properties.setProvider("kimi");
            properties.getKimi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            properties.setModelMaxRetries(2);
            properties.setModelRetryBaseDelayMs(0);
            properties.setModelRetryMaxDelayMs(0);
            OpenAiCompatibleAgentChatModelClient retryingClient =
                    new OpenAiCompatibleAgentChatModelClient(properties, new AgentJsonCodecUtil());
            ModelResult result = retryingClient.chat(new ModelRequest("return json"));
            assertTrue(result instanceof ModelResult.Success);
            assertEquals(3, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("expected false");
        }
    }
}
