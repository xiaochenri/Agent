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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@RestController
public class AgentDemoController {

    private static final int MAX_TURNS_PER_SESSION = 10;
    private static final Duration DUPLICATE_REQUEST_WINDOW = Duration.ofMinutes(10);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReActAgent reActAgent;
    private final UserMemoryService userMemoryService;
    private final ConcurrentMap<String, Deque<ChatTurn>> sessionTurns = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedReply> userRequestCache = new ConcurrentHashMap<>();
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

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

        Optional<CachedReply> cachedReply = findCachedReply(sessionId, userId, input);
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
            cacheReply(sessionId, userId, input, memoryWriteReply.get());
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
        cacheReply(sessionId, userId, input, reply);

        synchronized (turns) {
            turns.addLast(new ChatTurn(input, reply, payload));
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

    @PostMapping(path = "/api/agent/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.execute(() -> {
            try {
                handleChatStream(request, emitter);
                emitter.complete();
            } catch (Exception e) {
                try {
                    sendSse(emitter, "error", Map.of("message", normalize(e.getMessage(), "流式请求失败")));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @PostMapping(path = "/api/agent/chat/model-stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chatModelStream(@RequestBody ChatRequest request) {
        Sinks.Many<ServerSentEvent<Map<String, Object>>> sink = Sinks.many().unicast().onBackpressureBuffer();
        streamExecutor.execute(() -> {
            try {
                handleChatModelStream(request, sink);
                sink.tryEmitComplete();
            } catch (Exception e) {
                emitFluxEvent(sink, "error", Map.of("message", normalize(e.getMessage(), "模型流式请求失败")));
                sink.tryEmitComplete();
            }
        });
        return sink.asFlux();
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
        appendPendingClarificationContext(sb, history);
        sb.append("本轮用户: ").append(input);
        return sb.toString();
    }

    private void handleChatStream(ChatRequest request, SseEmitter emitter) throws Exception {
        String sessionId = normalize(request.sessionId(), "chat-session");
        String userId = normalize(request.userId(), "chat-user");
        String input = normalize(request.input(), "");
        if (input.isBlank()) {
            sendSse(emitter, "meta", Map.of(
                    "sessionId", sessionId,
                    "userId", userId,
                    "modelCallCount", 0,
                    "modelCallDetails", List.of()));
            sendTextDeltas(emitter, "input 不能为空");
            sendSse(emitter, "done", Map.of("reply", "input 不能为空"));
            return;
        }

        Optional<CachedReply> cachedReply = findCachedReply(sessionId, userId, input);
        if (cachedReply.isPresent()) {
            List<String> details = List.of("命中重复请求缓存，复用最近一次回复");
            sendSse(emitter, "process", Map.of("message", details.get(0)));
            sendSse(emitter, "meta", Map.of(
                    "sessionId", sessionId,
                    "userId", userId,
                    "modelCallCount", 0,
                    "modelCallDetails", details));
            sendTextDeltas(emitter, cachedReply.get().reply());
            sendSse(emitter, "done", Map.of("reply", cachedReply.get().reply()));
            return;
        }

        Optional<String> memoryWriteReply = userMemoryService.tryWriteByInputCommand(userId, input);
        if (memoryWriteReply.isPresent()) {
            cacheReply(sessionId, userId, input, memoryWriteReply.get());
            sendSse(emitter, "process", Map.of("message", "已识别为记忆写入指令"));
            sendSse(emitter, "meta", Map.of(
                    "sessionId", sessionId,
                    "userId", userId,
                    "modelCallCount", 0,
                    "modelCallDetails", List.of()));
            sendTextDeltas(emitter, memoryWriteReply.get());
            sendSse(emitter, "done", Map.of("reply", memoryWriteReply.get()));
            return;
        }

        Deque<ChatTurn> turns = sessionTurns.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        List<ChatTurn> history;
        synchronized (turns) {
            history = new ArrayList<>(turns);
        }
        sendSse(emitter, "process", Map.of("message", "已载入会话历史 " + history.size() + " 轮"));
        String memoryPrompt = userMemoryService.buildMemoryPrompt(userId);
        if (!memoryPrompt.isBlank()) {
            sendSse(emitter, "process", Map.of("message", "已载入用户长期记忆"));
        }

        String promptWithHistory = buildPromptWithHistory(input, history, memoryPrompt);
        sendSse(emitter, "process", Map.of("message", "Agent 开始规划、调用工具并生成回答"));
        String rawReply = reActAgent.call(promptWithHistory, sessionId, userId);
        Map<String, Object> payload = parsePayload(rawReply);
        emitProcessFromPayload(emitter, payload);

        String reply = extractConclusionText(payload, rawReply);
        ModelCallStats modelCallStats = extractModelCallStats(payload);
        cacheReply(sessionId, userId, input, reply);
        synchronized (turns) {
            turns.addLast(new ChatTurn(input, reply, payload));
            while (turns.size() > MAX_TURNS_PER_SESSION) {
                turns.removeFirst();
            }
        }

        sendSse(emitter, "meta", Map.of(
                "sessionId", sessionId,
                "userId", userId,
                "modelCallCount", modelCallStats.count(),
                "modelCallDetails", modelCallStats.details()));
        sendTextDeltas(emitter, reply);
        sendSse(emitter, "done", Map.of("reply", reply));
    }

    private void handleChatModelStream(
            ChatRequest request, Sinks.Many<ServerSentEvent<Map<String, Object>>> sink) {
        String sessionId = normalize(request.sessionId(), "chat-session");
        String userId = normalize(request.userId(), "chat-user");
        String input = normalize(request.input(), "");
        if (input.isBlank()) {
            emitFluxEvent(sink, "meta", Map.of(
                    "sessionId", sessionId,
                    "userId", userId,
                    "modelCallCount", 0,
                    "modelCallDetails", List.of()));
            sendTextDeltas(sink, "input 不能为空");
            emitFluxEvent(sink, "done", Map.of("reply", "input 不能为空"));
            return;
        }

        Optional<CachedReply> cachedReply = findCachedReply(sessionId, userId, input);
        if (cachedReply.isPresent()) {
            List<String> details = List.of("命中重复请求缓存，复用最近一次回复");
            emitFluxEvent(sink, "process", Map.of("message", details.get(0)));
            emitFluxEvent(sink, "meta", Map.of(
                    "sessionId", sessionId,
                    "userId", userId,
                    "modelCallCount", 0,
                    "modelCallDetails", details));
            sendTextDeltas(sink, cachedReply.get().reply());
            emitFluxEvent(sink, "done", Map.of("reply", cachedReply.get().reply()));
            return;
        }

        Optional<String> memoryWriteReply = userMemoryService.tryWriteByInputCommand(userId, input);
        if (memoryWriteReply.isPresent()) {
            cacheReply(sessionId, userId, input, memoryWriteReply.get());
            emitFluxEvent(sink, "process", Map.of("message", "已识别为记忆写入指令"));
            emitFluxEvent(sink, "meta", Map.of(
                    "sessionId", sessionId,
                    "userId", userId,
                    "modelCallCount", 0,
                    "modelCallDetails", List.of()));
            sendTextDeltas(sink, memoryWriteReply.get());
            emitFluxEvent(sink, "done", Map.of("reply", memoryWriteReply.get()));
            return;
        }

        Deque<ChatTurn> turns = sessionTurns.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        List<ChatTurn> history;
        synchronized (turns) {
            history = new ArrayList<>(turns);
        }
        emitFluxEvent(sink, "process", Map.of("message", "已载入会话历史 " + history.size() + " 轮"));
        String memoryPrompt = userMemoryService.buildMemoryPrompt(userId);
        if (!memoryPrompt.isBlank()) {
            emitFluxEvent(sink, "process", Map.of("message", "已载入用户长期记忆"));
        }

        String promptWithHistory = buildPromptWithHistory(input, history, memoryPrompt);
        String[] rawReplyHolder = new String[] {""};
        for (Map<String, Object> event : reActAgent.callWithModelFlux(promptWithHistory, sessionId, userId).toIterable()) {
            String type = normalize(event.get("type") == null ? null : String.valueOf(event.get("type")), "");
            if ("raw_reply".equals(type)) {
                rawReplyHolder[0] = normalize(
                        event.get("rawReply") == null ? null : String.valueOf(event.get("rawReply")),
                        "");
                continue;
            }
            String message = normalize(event.get("message") == null ? null : String.valueOf(event.get("message")), "");
            if (!message.isBlank()) {
                emitFluxEvent(sink, "process", Map.of("message", message));
            }
        }
        String rawReply = rawReplyHolder[0];
        Map<String, Object> payload = parsePayload(rawReply);
        String reply = extractConclusionText(payload, rawReply);
        ModelCallStats modelCallStats = extractModelCallStats(payload);
        cacheReply(sessionId, userId, input, reply);
        synchronized (turns) {
            turns.addLast(new ChatTurn(input, reply, payload));
            while (turns.size() > MAX_TURNS_PER_SESSION) {
                turns.removeFirst();
            }
        }

        emitFluxEvent(sink, "meta", Map.of(
                "sessionId", sessionId,
                "userId", userId,
                "modelCallCount", modelCallStats.count(),
                "modelCallDetails", modelCallStats.details()));
        sendTextDeltas(sink, reply);
        emitFluxEvent(sink, "done", Map.of("reply", reply));
    }

    private void emitProcessFromPayload(SseEmitter emitter, Map<String, Object> payload) throws Exception {
        Object executionLogObj = payload.get("execution_log");
        if (!(executionLogObj instanceof List<?> logs)) {
            return;
        }
        int index = 0;
        for (Object logObj : logs) {
            if (!(logObj instanceof Map<?, ?> logMap)) {
                continue;
            }
            index += 1;
            String step = normalize(logMap.get("step") == null ? null : logMap.get("step").toString(), "step_" + index);
            String toolName = normalize(logMap.get("tool_name") == null ? null : logMap.get("tool_name").toString(), "");
            List<String> calledTools = extractCalledTools(logMap.get("output"));
            String message;
            if ("reasoning".equals(toolName) || step.startsWith("reasoning_round_")) {
                message = "第 " + index + " 步：" + inferPurpose(calledTools, logMap.get("output"));
            } else {
                message = "第 " + index + " 步：调用工具 " + toolName;
            }
            sendSse(emitter, "process", Map.of("message", message));
        }
    }

    private void sendTextDeltas(SseEmitter emitter, String text) throws Exception {
        String normalizedText = normalize(text, "");
        if (normalizedText.isBlank()) {
            return;
        }
        for (int offset = 0; offset < normalizedText.length(); ) {
            int next = normalizedText.offsetByCodePoints(offset, 1);
            sendSse(emitter, "reply_delta", Map.of("text", normalizedText.substring(offset, next)));
            offset = next;
        }
    }

    private void sendTextDeltas(Sinks.Many<ServerSentEvent<Map<String, Object>>> sink, String text) {
        String normalizedText = normalize(text, "");
        if (normalizedText.isBlank()) {
            return;
        }
        for (int offset = 0; offset < normalizedText.length(); ) {
            int next = normalizedText.offsetByCodePoints(offset, 1);
            emitFluxEvent(sink, "reply_delta", Map.of("text", normalizedText.substring(offset, next)));
            offset = next;
        }
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) throws Exception {
        emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
    }

    private void emitFluxEvent(
            Sinks.Many<ServerSentEvent<Map<String, Object>>> sink, String eventName, Map<String, Object> data) {
        sink.tryEmitNext(ServerSentEvent.<Map<String, Object>>builder()
                .event(eventName)
                .data(data)
                .build());
    }

    private void appendPendingClarificationContext(StringBuilder sb, List<ChatTurn> history) {
        if (history.isEmpty()) {
            return;
        }
        Map<String, Object> pendingClarification = extractLatestPendingClarification(history);
        if (pendingClarification.isEmpty()) {
            return;
        }
        sb.append("上一轮存在结构化澄清上下文。")
                .append("如果本轮用户只输入 A/B/C、确认、取消、或某个候选值，请优先按该澄清上下文解释，而不是当作新任务。\n");
        appendIfPresent(sb, "澄清ID", pendingClarification.get("id"));
        appendIfPresent(sb, "原始请求", pendingClarification.get("original_user_input"));
        appendIfPresent(sb, "缺失槽位", pendingClarification.get("missing_slots"));
        appendIfPresent(sb, "候选选项", pendingClarification.get("suggested_options"));
        appendIfPresent(sb, "默认假设", pendingClarification.get("default_assumption"));
        appendIfPresent(sb, "恢复策略", pendingClarification.get("resume_policy"));
        appendIfPresent(sb, "槽位详情", pendingClarification.get("slots"));
        sb.append("请将本轮用户回复与上述澄清上下文合并，形成完整任务后继续执行。\n");
    }

    private Map<String, Object> extractLatestPendingClarification(List<ChatTurn> history) {
        ChatTurn lastTurn = history.get(history.size() - 1);
        return extractPendingClarification(lastTurn.payload());
    }

    private Map<String, Object> extractPendingClarification(Map<String, Object> payload) {
        Object executionLogObj = payload.get("execution_log");
        if (!(executionLogObj instanceof List<?> logs)) {
            return Map.of();
        }
        for (int i = logs.size() - 1; i >= 0; i--) {
            Object logObj = logs.get(i);
            if (!(logObj instanceof Map<?, ?> logMap)) {
                continue;
            }
            String toolName = normalize(logMap.get("tool_name") == null ? null : logMap.get("tool_name").toString(), "");
            if (!"clarify_requirement".equals(toolName)) {
                continue;
            }
            Map<String, Object> output = asStringObjectMap(logMap.get("output"));
            Map<String, Object> data = asStringObjectMap(output.get("data"));
            Map<String, Object> pending = asStringObjectMap(data.get("pending_clarification"));
            if (!pending.isEmpty()) {
                return pending;
            }
        }
        return Map.of();
    }

    private Map<String, Object> asStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private void appendIfPresent(StringBuilder sb, String label, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        sb.append(label).append(": ").append(toPromptValue(value)).append("\n");
    }

    private String toPromptValue(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
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
        List<String> conclusions = sanitizeUserVisibleItems(finalAnswerMap.get("core_conclusions"));
        List<String> evidence = sanitizeUserVisibleItems(finalAnswerMap.get("key_evidence"));
        List<String> risks = sanitizeUserVisibleItems(finalAnswerMap.get("risk_points"));
        List<String> actions = sanitizeUserVisibleItems(finalAnswerMap.get("next_actions"));
        String formatted = isDirectReply(payload)
                ? formatDirectReply(conclusions, actions)
                : formatUserVisibleReply(conclusions, evidence, risks, actions);
        if (formatted.isBlank()) {
            return normalizedRaw;
        }
        return formatted;
    }

    private boolean isDirectReply(Map<String, Object> payload) {
        Object executionLogObj = payload.get("execution_log");
        if (!(executionLogObj instanceof List<?> logs)) {
            return false;
        }
        for (Object logObj : logs) {
            if (!(logObj instanceof Map<?, ?> logMap)) {
                continue;
            }
            String step = normalize(logMap.get("step") == null ? null : logMap.get("step").toString(), "");
            String toolName = normalize(logMap.get("tool_name") == null ? null : logMap.get("tool_name").toString(), "");
            if ("direct_reply".equals(step) || "direct_reply_module".equals(toolName)) {
                return true;
            }
        }
        return false;
    }

    private List<String> sanitizeUserVisibleItems(Object value) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                addUserVisibleItem(lines, item);
            }
            return new ArrayList<>(lines);
        }
        addUserVisibleItem(lines, value);
        return new ArrayList<>(lines);
    }

    private void addUserVisibleItem(LinkedHashSet<String> lines, Object value) {
        String text = sanitizeUserVisibleText(value);
        if (!text.isEmpty()) {
            lines.add(text);
        }
    }

    private String sanitizeUserVisibleText(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || isInternalAgentText(text)) {
            return "";
        }
        return text
                .replace("已识别信息：", "已知信息：")
                .replace("缺失信息：", "还需要补充：")
                .replace("缺少", "需要补充")
                .replace("分析维度缺失", "需要补充分析维度")
                .replace("无法调用任何业务工具", "暂时不能开始分析")
                .replace("继续执行将产生错误结果", "继续分析可能不准确")
                .replace("执行偏差", "分析偏差")
                .trim();
    }

    private boolean isInternalAgentText(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return true;
        }
        List<String> internalMarkers = List.of(
                "P0",
                "P1",
                "P2",
                "tool_calls",
                "工具链",
                "无需调用工具",
                "无需进入工具",
                "属于寒暄",
                "路由",
                "当前任务为",
                "决策：",
                "validation",
                "retryable",
                "blocked_reason",
                "risk_flags");
        for (String marker : internalMarkers) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String formatUserVisibleReply(
            List<String> conclusions, List<String> evidence, List<String> risks, List<String> actions) {
        StringBuilder sb = new StringBuilder();
        appendParagraphs(sb, conclusions);
        appendSection(sb, "已知信息", evidence);
        appendSection(sb, "需要注意", risks);
        appendSection(sb, "下一步", actions);
        return sb.toString().trim();
    }

    private String formatDirectReply(List<String> conclusions, List<String> actions) {
        StringBuilder sb = new StringBuilder();
        appendParagraphs(sb, conclusions);
        List<String> usefulActions = actions.stream()
                .filter(item -> item != null && !item.isBlank())
                .filter(item -> !item.contains("如需我执行任务"))
                .toList();
        appendSection(sb, "你可以这样问", usefulActions);
        return sb.toString().trim();
    }

    private void appendParagraphs(StringBuilder sb, List<String> items) {
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(item.trim());
        }
    }

    private void appendSection(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append("### ").append(title).append("\n");
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            sb.append("- ").append(item.trim()).append("\n");
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

    private Optional<CachedReply> findCachedReply(String sessionId, String userId, String input) {
        String cacheKey = buildCacheKey(sessionId, userId, input);
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

    private void cacheReply(String sessionId, String userId, String input, String reply) {
        String cacheKey = buildCacheKey(sessionId, userId, input);
        userRequestCache.put(cacheKey, new CachedReply(reply, Instant.now()));
    }

    private String buildCacheKey(String sessionId, String userId, String input) {
        return userId + "\n" + sessionId + "\n" + input;
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

    private record ChatTurn(String userInput, String assistantReply, Map<String, Object> payload) {
    }

    private record ModelCallStats(int count, List<String> details) {
    }

    private record CachedReply(String reply, Instant createdAt) {
    }
}
