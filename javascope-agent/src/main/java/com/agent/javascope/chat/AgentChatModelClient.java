package com.agent.javascope.chat;

import com.agent.javascope.util.AgentJsonCodecUtil;
import com.agent.javascope.config.AgentRuntimeProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AgentChatModelClient {

    private static final Logger LOG = LoggerFactory.getLogger(AgentChatModelClient.class);

    private final AgentRuntimeProperties properties;
    private final AgentJsonCodecUtil jsonCodec;
    private final HttpClient httpClient;

    public AgentChatModelClient(AgentRuntimeProperties properties, AgentJsonCodecUtil jsonCodec) {
        this.properties = properties;
        this.jsonCodec = jsonCodec;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public String chat(String prompt) {
        String apiKey = jsonCodec.normalize(properties.getApiKey(), "");
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("stockmind.agent-runtime.api-key 未配置，无法调用大模型 API。");
        }
        String systemPrompt = jsonCodec.normalize(properties.getSystemInstruction(), "你是通用执行器。");
        String userPrompt = jsonCodec.normalize(prompt, "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", jsonCodec.normalize(properties.getModel(), ""));
        body.put("temperature", properties.getTemperature());
        body.put("response_format", Map.of("type", "json_object"));
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        logModelRequest(body);
        logModelRequestText(systemPrompt, userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonCodec.toJson(body), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            logModelRawResponse(response.statusCode(), response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "{}";
            }
            Map<String, Object> parsed = jsonCodec.parseJson(response.body());
            Object choicesObj = parsed.get("choices");
            if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
                return "{}";
            }
            Map<String, Object> first = jsonCodec.asMap(choices.get(0));
            Map<String, Object> message = jsonCodec.asMap(first.get("message"));
            String content = jsonCodec.normalize((String) message.get("content"), "{}");
            logModelParsedContent(content);
            logModelResponseText(content);
            return content;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("agent_model_io={}", jsonCodec.toJson(Map.of(
                    "event", "model_exception",
                    "error_type", "InterruptedException",
                    "message", jsonCodec.normalize(e.getMessage(), ""))));
            return "{}";
        } catch (IOException e) {
            LOG.warn("agent_model_io={}", jsonCodec.toJson(Map.of(
                    "event", "model_exception",
                    "error_type", "IOException",
                    "message", jsonCodec.normalize(e.getMessage(), ""))));
            return "{}";
        }
    }

    public String chatStream(String prompt, Consumer<String> deltaConsumer) {
        String apiKey = jsonCodec.normalize(properties.getApiKey(), "");
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("stockmind.agent-runtime.api-key 未配置，无法调用大模型 API。");
        }
        String systemPrompt = jsonCodec.normalize(properties.getSystemInstruction(), "你是通用执行器。");
        String userPrompt = jsonCodec.normalize(prompt, "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", jsonCodec.normalize(properties.getModel(), ""));
        body.put("temperature", properties.getTemperature());
        body.put("response_format", Map.of("type", "json_object"));
        body.put("stream", true);
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        logModelRequest(body);
        logModelRequestText(systemPrompt, userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonCodec.toJson(body), StandardCharsets.UTF_8))
                .build();

        StringBuilder content = new StringBuilder();
        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseText;
                try (Stream<String> lines = response.body()) {
                    responseText = String.join("\n", lines.toList());
                }
                logModelRawResponse(response.statusCode(), responseText);
                return "{}";
            }
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> consumeStreamLine(line, content, deltaConsumer));
            }
            String fullContent = jsonCodec.normalize(content.toString(), "{}");
            logModelParsedContent(fullContent);
            logModelResponseText(fullContent);
            return fullContent;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("agent_model_io={}", jsonCodec.toJson(Map.of(
                    "event", "model_stream_exception",
                    "error_type", "InterruptedException",
                    "message", jsonCodec.normalize(e.getMessage(), ""))));
            return "{}";
        } catch (IOException e) {
            LOG.warn("agent_model_io={}", jsonCodec.toJson(Map.of(
                    "event", "model_stream_exception",
                    "error_type", "IOException",
                    "message", jsonCodec.normalize(e.getMessage(), ""))));
            return "{}";
        }
    }

    private void consumeStreamLine(String line, StringBuilder content, Consumer<String> deltaConsumer) {
        String normalizedLine = jsonCodec.normalize(line, "");
        if (normalizedLine.isEmpty() || !normalizedLine.startsWith("data:")) {
            return;
        }
        String data = normalizedLine.substring("data:".length()).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return;
        }
        Map<String, Object> parsed = jsonCodec.parseJson(data);
        Object choicesObj = parsed.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return;
        }
        Map<String, Object> first = jsonCodec.asMap(choices.get(0));
        Map<String, Object> delta = jsonCodec.asMap(first.get("delta"));
        Object contentObj = delta.get("content");
        if (contentObj == null) {
            return;
        }
        String text = String.valueOf(contentObj);
        content.append(text);
        if (deltaConsumer != null && !text.isBlank()) {
            deltaConsumer.accept(text);
        }
    }

    private void logModelRequest(Map<String, Object> body) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("event", "model_request");
        log.put("provider", jsonCodec.normalize(properties.getProvider(), ""));
        log.put("base_url", resolveBaseUrl());
        log.put("path", "/chat/completions");
        log.put("body", body);
        LOG.info("agent_model_io={}", jsonCodec.toJson(log));
    }

    private void logModelRawResponse(int statusCode, String responseBody) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("event", "model_response_raw");
        log.put("status_code", statusCode);
        log.put("body", jsonCodec.normalize(responseBody, ""));
        LOG.info("agent_model_io={}", jsonCodec.toJson(log));
    }

    private void logModelParsedContent(String content) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("event", "model_response_content");
        log.put("content", jsonCodec.normalize(content, "{}"));
        LOG.info("agent_model_io={}", jsonCodec.toJson(log));
    }

    private void logModelRequestText(String systemPrompt, String userPrompt) {
        String merged = "[SYSTEM]\n" + systemPrompt + "\n\n[USER]\n" + userPrompt;
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("event", "model_request_text");
        log.put("text", merged);
        LOG.info("agent_model_text={}", jsonCodec.toJson(log));
    }

    private void logModelResponseText(String responseText) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("event", "model_response_text");
        log.put("text", jsonCodec.normalize(responseText, "{}"));
        LOG.info("agent_model_text={}", jsonCodec.toJson(log));
    }

    private String resolveBaseUrl() {
        String configured = jsonCodec.normalize(properties.getBaseUrl(), "");
        if (!configured.isEmpty()) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        return "";
    }
}
