package com.stockmind.bootstrap;

import com.agent.javascope.agent.ReActAgent;
import com.stockmind.bootstrap.memory.UserMemoryEntry;
import com.stockmind.bootstrap.memory.UserMemoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AgentDemoController {

    private static final int MAX_TURNS_PER_SESSION = 10;
    private static final Duration DUPLICATE_REQUEST_WINDOW = Duration.ofMinutes(10);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReActAgent reActAgent;
    private final UserMemoryService userMemoryService;
    private final ConcurrentMap<String, Deque<ChatTurn>> sessionTurns = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedReply> userRequestCache = new ConcurrentHashMap<>();

    public AgentDemoController(ReActAgent reActAgent, UserMemoryService userMemoryService) {
        this.reActAgent = reActAgent;
        this.userMemoryService = userMemoryService;
    }

    @GetMapping("/api/agent/demo")
    public String demo(@RequestParam(name = "input", defaultValue = "AAPL") String input,
                       @RequestParam(name = "sessionId", defaultValue = "demo-session") String sessionId,
                       @RequestParam(name = "userId", defaultValue = "demo-user") String userId) {
        return reActAgent.call(input, sessionId, userId);
    }

    @GetMapping(path = "/api/agent/demo/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter demoStream(@RequestParam(name = "input", defaultValue = "AAPL") String input,
                                 @RequestParam(name = "sessionId", defaultValue = "demo-session") String sessionId,
                                 @RequestParam(name = "userId", defaultValue = "demo-user") String userId) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            reActAgent.callStream(input, sessionId, userId, chunk -> {
                try {
                    emitter.send(SseEmitter.event().name("agent_reply").data(chunk));
                } catch (Exception sendError) {
                    throw new RuntimeException(sendError);
                }
            });
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @PostMapping(path = "/api/agent/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        String sessionId = normalize(request.sessionId(), "chat-session");
        String userId = normalize(request.userId(), "chat-user");
        String input = normalize(request.input(), "");
        if (input.isBlank()) {
            return Map.of(
                    "sessionId", sessionId,
                    "userId", userId,
                    "reply", "",
                    "modelCallCount", 0,
                    "modelCallDetails", List.of(),
                    "error", "input 不能为空");
        }

        Optional<CachedReply> cachedReply = findCachedReply(userId, input);
        if (cachedReply.isPresent()) {
            return Map.of(
                    "sessionId", sessionId,
                    "userId", userId,
                    "reply", cachedReply.get().reply(),
                    "modelCallCount", 0,
                    "modelCallDetails", List.of("命中重复请求缓存，复用最近一次回复"));
        }

        Optional<String> memoryWriteReply = userMemoryService.tryWriteByInputCommand(userId, input);
        if (memoryWriteReply.isPresent()) {
            cacheReply(userId, input, memoryWriteReply.get());
            return Map.of(
                    "sessionId", sessionId,
                    "userId", userId,
                    "reply", memoryWriteReply.get(),
                    "modelCallCount", 0,
                    "modelCallDetails", List.of());
        }

        Deque<ChatTurn> turns = sessionTurns.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        List<ChatTurn> history;
        synchronized (turns) {
            history = new ArrayList<>(turns);
        }
        String memoryPrompt = userMemoryService.buildMemoryPrompt(userId);
        String promptWithHistory = buildPromptWithHistory(input, history, memoryPrompt);
        String rawReply = reActAgent.call(promptWithHistory, sessionId, userId);
        Map<String, Object> payload = parsePayload(rawReply);
        String reply = extractConclusionText(payload, rawReply);
        ModelCallStats modelCallStats = extractModelCallStats(payload);
        cacheReply(userId, input, reply);

        synchronized (turns) {
            turns.addLast(new ChatTurn(input, reply));
            while (turns.size() > MAX_TURNS_PER_SESSION) {
                turns.removeFirst();
            }
        }
        return Map.of(
                "sessionId", sessionId,
                "userId", userId,
                "reply", reply,
                "modelCallCount", modelCallStats.count(),
                "modelCallDetails", modelCallStats.details());
    }

    @GetMapping(path = "/api/agent/memory", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> listMemory(@RequestParam(name = "userId", defaultValue = "chat-user") String userId) {
        String normalizedUserId = normalize(userId, "chat-user");
        List<Map<String, Object>> items = userMemoryService.list(normalizedUserId).stream()
                .map(this::toMemoryItem)
                .toList();
        return Map.of(
                "userId", normalizedUserId,
                "items", items);
    }

    @PutMapping(path = "/api/agent/memory/{key}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> putMemory(@PathVariable("key") String key,
                                         @RequestParam(name = "userId", defaultValue = "chat-user") String userId,
                                         @RequestBody MemoryUpsertRequest request) {
        String normalizedUserId = normalize(userId, "chat-user");
        if (!userMemoryService.isSupportedKey(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的 key: " + key);
        }
        if (request == null || request.value() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value 不能为空");
        }
        userMemoryService.putExplicitMemory(normalizedUserId, key, request.value(), request.ttlSeconds());
        return Map.of(
                "userId", normalizedUserId,
                "key", key,
                "status", "ok");
    }

    @DeleteMapping(path = "/api/agent/memory/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteMemory(@PathVariable("key") String key,
                                            @RequestParam(name = "userId", defaultValue = "chat-user") String userId) {
        String normalizedUserId = normalize(userId, "chat-user");
        userMemoryService.delete(normalizedUserId, key);
        return Map.of(
                "userId", normalizedUserId,
                "key", key,
                "status", "deleted");
    }

    private String buildPromptWithHistory(String input, List<ChatTurn> history, String memoryPrompt) {
        if (history.isEmpty() && memoryPrompt.isBlank()) {
            return input;
        }
        StringBuilder sb = new StringBuilder();
        if (!memoryPrompt.isBlank()) {
            sb.append(memoryPrompt);
        }
        sb.append("以下是同一会话的历史对话，请保持上下文一致。\n");
        for (int i = 0; i < history.size(); i++) {
            ChatTurn turn = history.get(i);
            sb.append("第").append(i + 1).append("轮用户: ").append(turn.userInput()).append("\n");
            sb.append("第").append(i + 1).append("轮助手: ").append(turn.assistantReply()).append("\n");
        }
        sb.append("本轮用户: ").append(input);
        return sb.toString();
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String extractConclusionText(Map<String, Object> payload, String rawReply) {
        String normalizedRaw = normalize(rawReply, "");
        if (normalizedRaw.isBlank()) {
            return "";
        }
        Object finalAnswerObj = payload.get("final_answer");
        if (!(finalAnswerObj instanceof Map<?, ?> finalAnswerMap)) {
            return normalizedRaw;
        }
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        appendValues(lines, finalAnswerMap.get("core_conclusions"));
        appendValues(lines, finalAnswerMap.get("key_evidence"));
        appendValues(lines, finalAnswerMap.get("risk_points"));
        appendValues(lines, finalAnswerMap.get("next_actions"));
        if (lines.isEmpty()) {
            return normalizedRaw;
        }
        return lines.stream().collect(Collectors.joining("\n"));
    }

    private void appendValues(LinkedHashSet<String> lines, Object value) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = item.toString().trim();
                if (!text.isEmpty()) {
                    lines.add(text);
                }
            }
            return;
        }
        if (value == null) {
            return;
        }
        String text = value.toString().trim();
        if (!text.isEmpty()) {
            lines.add(text);
        }
    }

    private Map<String, Object> parsePayload(String rawReply) {
        String normalizedRaw = normalize(rawReply, "");
        if (normalizedRaw.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(normalizedRaw, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private ModelCallStats extractModelCallStats(Map<String, Object> payload) {
        Object executionLogObj = payload.get("execution_log");
        if (!(executionLogObj instanceof List<?> logs)) {
            return new ModelCallStats(0, List.of());
        }
        List<String> details = new ArrayList<>();
        for (Object logObj : logs) {
            if (!(logObj instanceof Map<?, ?> logMap)) {
                continue;
            }
            String step = normalize(logMap.get("step") == null ? null : logMap.get("step").toString(), "");
            String toolName = normalize(logMap.get("tool_name") == null ? null : logMap.get("tool_name").toString(), "");
            if (!step.startsWith("reasoning_round_") && !"reasoning".equals(toolName)) {
                continue;
            }
            List<String> calledTools = extractCalledTools(logMap.get("output"));
            String purpose = inferPurpose(calledTools, logMap.get("output"));
            int callIndex = details.size() + 1;
            if (calledTools.isEmpty()) {
                details.add("第" + callIndex + "次："
                        + purpose);
            } else {
                details.add("第" + callIndex + "次：" + purpose + "（工具：" + String.join(", ", calledTools) + "）");
            }
        }
        return new ModelCallStats(details.size(), details);
    }

    private List<String> extractCalledTools(Object outputObj) {
        if (!(outputObj instanceof Map<?, ?> outputMap)) {
            return List.of();
        }
        Object toolCallsObj = outputMap.get("tool_calls");
        if (!(toolCallsObj instanceof List<?> toolCalls)) {
            return List.of();
        }
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        for (Object toolCallObj : toolCalls) {
            if (!(toolCallObj instanceof Map<?, ?> toolCallMap)) {
                continue;
            }
            String toolName = normalize(toolCallMap.get("name") == null ? null : toolCallMap.get("name").toString(), "");
            if (!toolName.isBlank()) {
                tools.add(toolName);
            }
        }
        return new ArrayList<>(tools);
    }

    private String inferPurpose(List<String> calledTools, Object outputObj) {
        if (calledTools.contains("create_plan")) {
            return "制定执行计划";
        }
        if (calledTools.contains("revise_plan")) {
            return "修订执行计划";
        }
        if (!calledTools.isEmpty()) {
            return "决定调用业务工具执行步骤";
        }
        if (outputObj instanceof Map<?, ?> outputMap && outputMap.get("final_answer") != null) {
            return "生成最终结论";
        }
        return "通用推理";
    }

    private Optional<CachedReply> findCachedReply(String userId, String input) {
        String cacheKey = buildCacheKey(userId, input);
        CachedReply cachedReply = userRequestCache.get(cacheKey);
        if (cachedReply == null) {
            return Optional.empty();
        }
        if (cachedReply.createdAt().plus(DUPLICATE_REQUEST_WINDOW).isBefore(Instant.now())) {
            userRequestCache.remove(cacheKey, cachedReply);
            return Optional.empty();
        }
        return Optional.of(cachedReply);
    }

    private void cacheReply(String userId, String input, String reply) {
        String cacheKey = buildCacheKey(userId, input);
        userRequestCache.put(cacheKey, new CachedReply(reply, Instant.now()));
    }

    private String buildCacheKey(String userId, String input) {
        return userId + "\n" + input;
    }

    private Map<String, Object> toMemoryItem(UserMemoryEntry entry) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("key", entry.key());
        item.put("value", entry.value());
        item.put("scope", entry.scope());
        item.put("source", entry.source());
        item.put("updatedAt", entry.updatedAt() == null ? null : entry.updatedAt().toString());
        item.put("expireAt", entry.expireAt() == null ? null : entry.expireAt().toString());
        return item;
    }

    public record ChatRequest(String input, String sessionId, String userId) {
    }

    public record MemoryUpsertRequest(Object value, Long ttlSeconds) {
    }

    private record ChatTurn(String userInput, String assistantReply) {
    }

    private record ModelCallStats(int count, List<String> details) {
    }

    private record CachedReply(String reply, Instant createdAt) {
    }
}
