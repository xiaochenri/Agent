package com.agent.javascope.model.openai;

import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.model.ModelError;
import com.agent.javascope.model.ModelRequest;
import com.agent.javascope.model.ModelResult;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.json.AgentJsonCodecUtil;

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

/**
 * 基于 OpenAI Chat Completions 协议的默认模型客户端实现。
 *
 * <p>该类只负责协议适配；框架其他组件应依赖 {@link AgentChatModelClient} 接口。</p>
 */
public class OpenAiCompatibleAgentChatModelClient implements AgentChatModelClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleAgentChatModelClient.class);

    private final AgentRuntimeProperties properties;
    private final AgentJsonCodecUtil jsonCodec;
    private final HttpClient httpClient;

    public OpenAiCompatibleAgentChatModelClient(AgentRuntimeProperties properties, AgentJsonCodecUtil jsonCodec) {
        this.properties = properties;
        this.jsonCodec = jsonCodec;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public ModelResult chat(ModelRequest request) {
        String apiKey = jsonCodec.normalize(properties.getApiKey(), "");
        if (apiKey.isEmpty()) {
            return missingApiKeyFailure();
        }
        String systemPrompt = systemPrompt();
        String userPrompt = userPrompt(request);
        Map<String, Object> body = createRequestBody(systemPrompt, userPrompt, false);
        logModelRequest(body);
        logModelRequestText(systemPrompt, userPrompt);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(resolveBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonCodec.toJson(body), StandardCharsets.UTF_8))
                .build();

        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                logModelRawResponse(response.statusCode(), response.body());
                ModelResult result = parseChatResponse(response);
                if (shouldRetry(result, attempt) && waitBeforeRetry(attempt, response, result)) {
                    continue;
                }
                return result;
            } catch (InterruptedException e) {
                return interruptedFailure(e, "model_exception");
            } catch (IOException e) {
                ModelResult result = ioFailure(e, "model_exception");
                if (shouldRetry(result, attempt) && waitBeforeRetry(attempt, null, result)) {
                    continue;
                }
                return result;
            }
        }
    }

    @Override
    public ModelResult chatStream(ModelRequest request, Consumer<String> deltaConsumer) {
        String apiKey = jsonCodec.normalize(properties.getApiKey(), "");
        if (apiKey.isEmpty()) {
            return missingApiKeyFailure();
        }
        String systemPrompt = systemPrompt();
        String userPrompt = userPrompt(request);
        Map<String, Object> body = createRequestBody(systemPrompt, userPrompt, true);
        logModelRequest(body);
        logModelRequestText(systemPrompt, userPrompt);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(resolveBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonCodec.toJson(body), StandardCharsets.UTF_8))
                .build();

        for (int attempt = 0; ; attempt++) {
            StringBuilder content = new StringBuilder();
            try {
                HttpResponse<Stream<String>> response = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String responseText;
                    try (Stream<String> lines = response.body()) {
                        responseText = String.join("\n", lines.toList());
                    }
                    logModelRawResponse(response.statusCode(), responseText);
                    ModelResult result = httpFailure(response.statusCode(), responseText);
                    if (shouldRetry(result, attempt) && waitBeforeRetry(attempt, response, result)) {
                        continue;
                    }
                    return result;
                }
                try (Stream<String> lines = response.body()) {
                    lines.forEach(line -> consumeStreamLine(line, content, deltaConsumer));
                }
                String fullContent = jsonCodec.normalize(content.toString(), "");
                if (fullContent.isEmpty()) {
                    ModelResult result = failure("response_invalid", "模型流式响应未包含内容", true, response.statusCode());
                    if (shouldRetry(result, attempt) && waitBeforeRetry(attempt, response, result)) {
                        continue;
                    }
                    return result;
                }
                logModelParsedContent(fullContent);
                logModelResponseText(fullContent);
                return parseContent(fullContent, response.statusCode());
            } catch (InterruptedException e) {
                return interruptedFailure(e, "model_stream_exception");
            } catch (IOException e) {
                ModelResult result = ioFailure(e, "model_stream_exception");
                if (content.isEmpty() && shouldRetry(result, attempt) && waitBeforeRetry(attempt, null, result)) {
                    continue;
                }
                return result;
            }
        }
    }

    private ModelResult parseChatResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return httpFailure(response.statusCode(), response.body());
        }
        Map<String, Object> parsed;
        try {
            parsed = jsonCodec.parseJson(response.body());
        } catch (RuntimeException e) {
            return failure("response_invalid", "模型响应不是有效 JSON", true, response.statusCode());
        }
        Object choicesObj = parsed.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return failure("response_invalid", "模型响应缺少 choices", true, response.statusCode());
        }
        Map<String, Object> first = jsonCodec.asMap(choices.get(0));
        Map<String, Object> message = jsonCodec.asMap(first.get("message"));
        String content = jsonCodec.normalize((String) message.get("content"), "");
        if (content.isEmpty()) {
            return failure("response_invalid", "模型响应缺少 message.content", true, response.statusCode());
        }
        logModelParsedContent(content);
        logModelResponseText(content);
        return parseContent(content, response.statusCode());
    }

    private ModelResult parseContent(String content, int statusCode) {
        try {
            return new ModelResult.Success(jsonCodec.parseTree(content));
        } catch (Exception e) {
            return failure("content_not_json", "模型内容不是有效 JSON 对象", true, statusCode);
        }
    }

    Map<String, Object> createRequestBody(String systemPrompt, String userPrompt, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", jsonCodec.normalize(properties.getModel(), ""));
        addTemperatureIfEnabled(body);
        body.put("response_format", Map.of("type", "json_object"));
        if (stream) {
            body.put("stream", true);
        }
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        return body;
    }

    private String systemPrompt() {
        return jsonCodec.normalize(properties.getSystemInstruction(), "你是通用执行器。");
    }

    private String userPrompt(ModelRequest request) {
        return jsonCodec.normalize(request == null ? "" : request.prompt(), "");
    }

    private void addTemperatureIfEnabled(Map<String, Object> body) {
        if (properties.isTemperatureEnabled()) {
            body.put("temperature", properties.getTemperature());
        }
    }

    private ModelResult.Failure failure(String code, String message, boolean retryable, int statusCode) {
        return new ModelResult.Failure(new ModelError(code, message, retryable, statusCode));
    }

    private ModelResult.Failure httpFailure(int statusCode, String responseBody) {
        String code = "http_error";
        String message = "模型服务返回 HTTP " + statusCode;
        try {
            Map<String, Object> parsed = jsonCodec.parseJson(responseBody);
            Map<String, Object> error = jsonCodec.asMap(parsed.get("error"));
            code = firstNonBlank(error.get("type"), error.get("code"), code);
            message = firstNonBlank(error.get("message"), message);
        } catch (RuntimeException ignored) {
            // 非 JSON 错误页仍保留稳定的 http_error。
        }
        boolean retryable = statusCode == 429 || statusCode >= 500 || "engine_overloaded_error".equals(code);
        return failure(code, message, retryable, statusCode);
    }

    private ModelResult.Failure ioFailure(IOException exception, String event) {
        LOG.warn("agent_model_io={}", jsonCodec.toJson(Map.of(
                "event", event,
                "error_type", "IOException",
                "message", jsonCodec.normalize(exception.getMessage(), ""))));
        return failure("io_error", jsonCodec.normalize(exception.getMessage(), "模型网络调用失败"), true, 0);
    }

    private ModelResult.Failure interruptedFailure(InterruptedException exception, String event) {
        Thread.currentThread().interrupt();
        LOG.warn("agent_model_io={}", jsonCodec.toJson(Map.of(
                "event", event,
                "error_type", "InterruptedException",
                "message", jsonCodec.normalize(exception.getMessage(), ""))));
        return failure("interrupted", jsonCodec.normalize(exception.getMessage(), "模型调用被中断"), false, 0);
    }

    private boolean shouldRetry(ModelResult result, int attempt) {
        return result instanceof ModelResult.Failure failure
                && failure.error().retryable()
                && attempt < properties.getModelMaxRetries();
    }

    private boolean waitBeforeRetry(
            int attempt, HttpResponse<?> response, ModelResult result) {
        long delayMs = retryDelayMs(attempt, response);
        ModelError error = ((ModelResult.Failure) result).error();
        LOG.warn("agent_model_io={}", jsonCodec.toJson(Map.of(
                "event", "model_retry",
                "provider", jsonCodec.normalize(properties.getProvider(), ""),
                "error_code", error.code(),
                "status_code", error.statusCode(),
                "retry_number", attempt + 1,
                "max_retries", properties.getModelMaxRetries(),
                "delay_ms", delayMs)));
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long retryDelayMs(int attempt, HttpResponse<?> response) {
        if (response != null) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse("");
            try {
                if (!retryAfter.isBlank()) {
                    return Math.min(
                            properties.getModelRetryMaxDelayMs(),
                            Math.multiplyExact(Long.parseLong(retryAfter.trim()), 1000L));
                }
            } catch (ArithmeticException | NumberFormatException ignored) {
                // 无法解析时回退到本地指数退避。
            }
        }
        long baseDelayMs = properties.getModelRetryBaseDelayMs();
        long maxDelayMs = properties.getModelRetryMaxDelayMs();
        long multiplier = 1L << Math.min(attempt, 30);
        if (baseDelayMs > 0 && multiplier > maxDelayMs / Math.max(1L, baseDelayMs)) {
            return maxDelayMs;
        }
        return Math.min(maxDelayMs, baseDelayMs * multiplier);
    }

    private String firstNonBlank(Object first, Object second, String fallback) {
        String firstValue = first == null ? "" : String.valueOf(first).trim();
        if (!firstValue.isEmpty()) {
            return firstValue;
        }
        String secondValue = second == null ? "" : String.valueOf(second).trim();
        return secondValue.isEmpty() ? fallback : secondValue;
    }

    private String firstNonBlank(Object value, String fallback) {
        String normalized = value == null ? "" : String.valueOf(value).trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private ModelResult.Failure missingApiKeyFailure() {
        String provider = jsonCodec.normalize(properties.getProvider(), "当前 provider");
        return failure(
                "api_key_missing",
                "java.agent-runtime." + provider + ".api-key 未配置，无法调用大模型 API。",
                false,
                0);
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
