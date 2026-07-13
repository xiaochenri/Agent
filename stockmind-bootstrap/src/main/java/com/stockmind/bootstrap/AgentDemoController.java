package com.stockmind.bootstrap;

import com.agent.javascope.agent.runtime.ReActAgent;
import com.agent.javascope.user.application.ConversationApplicationService;
import com.agent.javascope.user.application.ConversationApplicationService.ChatResponse;
import com.agent.javascope.user.application.UserApplicationService;
import com.agent.javascope.user.conversation.Conversation;
import com.agent.javascope.user.conversation.ConversationMessage;
import com.agent.javascope.user.identity.CurrentUserProvider;
import com.agent.javascope.user.identity.UserAccount;
import com.agent.javascope.user.memory.UserMemory;
import com.agent.javascope.user.memory.UserMemoryService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@RestController
public class AgentDemoController {

    private final ReActAgent reActAgent;
    private final ConversationApplicationService conversations;
    private final UserMemoryService memories;
    private final CurrentUserProvider currentUserProvider;
    private final UserApplicationService users;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public AgentDemoController(
            ReActAgent reActAgent,
            ConversationApplicationService conversations,
            UserMemoryService memories,
            CurrentUserProvider currentUserProvider,
            UserApplicationService users) {
        this.reActAgent = reActAgent;
        this.conversations = conversations;
        this.memories = memories;
        this.currentUserProvider = currentUserProvider;
        this.users = users;
    }

    @GetMapping("/api/agent/demo")
    public String demo(
            @RequestParam(name = "input", defaultValue = "AAPL") String input,
            @RequestParam(name = "sessionId", defaultValue = "demo-session") String sessionId,
            @RequestParam(name = "userId", defaultValue = "demo-user") String userId) {
        return reActAgent.call(input, sessionId, userId);
    }

    @GetMapping(path = "/api/agent/demo/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter demoStream(
            @RequestParam(name = "input", defaultValue = "AAPL") String input,
            @RequestParam(name = "sessionId", defaultValue = "demo-session") String sessionId,
            @RequestParam(name = "userId", defaultValue = "demo-user") String userId) {
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.execute(() -> {
            try {
                reActAgent.callStream(input, sessionId, userId,
                        chunk -> sendUnchecked(emitter, "agent_reply", chunk));
                emitter.complete();
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    @PostMapping(path = "/api/v1/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    public Conversation createConversation(@RequestBody(required = false) CreateConversationRequest request) {
        CreateConversationRequest value = request == null ? new CreateConversationRequest("stock", null) : request;
        return conversations.create(currentUserId(), value.businessCode(), value.title());
    }

    @GetMapping(path = "/api/v1/users/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserAccount currentUser() {
        return users.getOrCreate(currentUserId());
    }

    @PatchMapping(path = "/api/v1/users/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserAccount updateCurrentUser(@RequestBody UpdateProfileRequest request) {
        return users.updateProfile(currentUserId(), request.displayName(), request.avatarUrl());
    }

    @GetMapping(path = "/api/v1/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Conversation> listConversations(
            @RequestParam(name = "businessCode", required = false) String businessCode,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return conversations.list(currentUserId(), businessCode, limit);
    }

    @GetMapping(path = "/api/v1/conversations/{conversationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Conversation getConversation(@PathVariable("conversationId") String conversationId) {
        return conversations.get(currentUserId(), conversationId);
    }

    @GetMapping(path = "/api/v1/conversations/{conversationId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ConversationMessage> listMessages(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(name = "beforeId", defaultValue = "0") long beforeId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return conversations.messages(currentUserId(), conversationId, beforeId, limit);
    }

    @PostMapping(path = "/api/v1/conversations/{conversationId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> sendMessage(
            @PathVariable("conversationId") String conversationId,
            @RequestBody SendMessageRequest request) {
        return toResponse(conversations.chat(
                currentUserId(), conversationId, request.requestId(), request.input()));
    }

    @PostMapping(path = "/api/v1/conversations/{conversationId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @PathVariable("conversationId") String conversationId,
            @RequestBody SendMessageRequest request) {
        return executeAsSse(currentUserId(), conversationId, request.requestId(), request.input());
    }

    @PatchMapping(path = "/api/v1/conversations/{conversationId}/archive")
    public Map<String, String> archiveConversation(
            @PathVariable("conversationId") String conversationId) {
        conversations.archive(currentUserId(), conversationId);
        return Map.of("status", "archived");
    }

    /** Backward-compatible endpoint. New clients should create a conversation and use /api/v1. */
    @PostMapping(path = "/api/agent/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        LegacyConversation legacy = legacyConversation(request);
        ChatResponse result = conversations.chat(
                legacy.userId(), legacy.internalId(), request.requestId(), request.input());
        Map<String, Object> response = toResponse(result);
        response.put("sessionId", legacy.externalId());
        response.put("userId", legacy.userId());
        appendModelStats(response, result.payload());
        return response;
    }

    @PostMapping(path = "/api/agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        LegacyConversation legacy = legacyConversation(request);
        return executeAsSse(legacy.userId(), legacy.internalId(), request.requestId(), request.input());
    }

    @PostMapping(path = "/api/agent/chat/model-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chatModelStream(@RequestBody ChatRequest request) {
        LegacyConversation legacy = legacyConversation(request);
        return Flux.<ServerSentEvent<Map<String, Object>>>create(sink -> streamExecutor.execute(() -> {
            try {
                ChatResponse result = conversations.chatStream(
                        legacy.userId(), legacy.internalId(), request.requestId(), request.input(),
                        processEvent -> sink.next(event(eventName(processEvent), processEvent)));
                sink.next(event("meta", Map.of(
                        "sessionId", legacy.externalId(), "userId", legacy.userId(),
                        "cached", result.cached())));
                emitDeltas(result.reply(), text -> sink.next(event("reply_delta", Map.of("text", text))));
                sink.next(event("done", toResponse(result)));
                sink.complete();
            } catch (Exception error) {
                sink.error(error);
            }
        }));
    }

    @GetMapping(path = "/api/agent/memory", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> listMemory(
            @RequestParam(name = "userId", defaultValue = "chat-user") String userId) {
        return Map.of("userId", userId, "items", memories.list(userId).stream().map(this::memoryItem).toList());
    }

    @PutMapping(path = "/api/agent/memory/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> putMemory(
            @PathVariable("key") String key,
            @RequestParam(name = "userId", defaultValue = "chat-user") String userId,
            @RequestParam(name = "businessCode", defaultValue = "stock") String businessCode,
            @RequestBody MemoryUpsertRequest request) {
        memories.put(userId, businessCode, key, request.value(), request.ttlSeconds());
        return Map.of("userId", userId, "key", key, "status", "ok");
    }

    @DeleteMapping(path = "/api/agent/memory/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteMemory(
            @PathVariable("key") String key,
            @RequestParam(name = "userId", defaultValue = "chat-user") String userId,
            @RequestParam(name = "businessCode", defaultValue = "stock") String businessCode) {
        memories.delete(userId, businessCode, key);
        return Map.of("userId", userId, "key", key, "status", "deleted");
    }

    private SseEmitter executeAsSse(
            String userId, String conversationId, String requestId, String input) {
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.execute(() -> {
            try {
                send(emitter, "process", Map.of("message", "已加载持久化会话上下文"));
                ChatResponse result = conversations.chatStream(
                        userId, conversationId, requestId, input,
                        event -> sendUnchecked(emitter, eventName(event), event));
                send(emitter, "meta", Map.of(
                        "conversationId", conversationId,
                        "requestId", result.requestId(),
                        "cached", result.cached()));
                emitDeltas(result.reply(), text -> sendUnchecked(emitter, "reply_delta", Map.of("text", text)));
                send(emitter, "done", toResponse(result));
                emitter.complete();
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    private LegacyConversation legacyConversation(ChatRequest request) {
        String userId = normalize(request.userId(), "chat-user");
        String externalId = normalize(request.sessionId(), "chat-session");
        String internalId = UUID.nameUUIDFromBytes(
                ("legacy\n" + userId + "\n" + externalId).getBytes(StandardCharsets.UTF_8)).toString();
        conversations.createWithId(userId, internalId, "stock", null);
        return new LegacyConversation(userId, externalId, internalId);
    }

    private Map<String, Object> toResponse(ChatResponse result) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", result.conversationId());
        response.put("requestId", result.requestId());
        response.put("reply", result.reply());
        response.put("executionId", result.executionId());
        response.put("cached", result.cached());
        for (String key : List.of("message", "data", "runtime", "metadata", "status", "route")) {
            if (result.payload().containsKey(key)) {
                response.put(key, result.payload().get(key));
            }
        }
        return response;
    }

    private void appendModelStats(Map<String, Object> target, Map<String, Object> payload) {
        List<String> details = new ArrayList<>();
        Object runtime = payload.get("runtime");
        if (runtime instanceof Map<?, ?> runtimeMap
                && runtimeMap.get("execution_log") instanceof List<?> logs) {
            for (Object item : logs) {
                if (!(item instanceof Map<?, ?> log)) {
                    continue;
                }
                String step = String.valueOf(log.get("step"));
                String tool = String.valueOf(log.get("tool_name"));
                if (step.startsWith("reasoning_round_") || "reasoning".equals(tool)) {
                    details.add("第" + (details.size() + 1) + "次：Agent 推理");
                }
            }
        }
        target.put("modelCallCount", details.size());
        target.put("modelCallDetails", details);
    }

    private Map<String, Object> memoryItem(UserMemory memory) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("namespace", memory.namespace());
        item.put("scopeType", memory.scopeType());
        item.put("scopeId", memory.scopeId());
        item.put("key", memory.key());
        item.put("value", memory.value());
        item.put("source", memory.source());
        item.put("updatedAt", memory.updatedAt());
        item.put("expireAt", memory.expireAt());
        return item;
    }

    private String currentUserId() {
        return currentUserProvider.currentUserId();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void emitDeltas(String text, DeltaConsumer consumer) {
        String value = text == null ? "" : text;
        for (int offset = 0; offset < value.length();) {
            int next = value.offsetByCodePoints(offset, 1);
            consumer.accept(value.substring(offset, next));
            offset = next;
        }
    }

    private void send(SseEmitter emitter, String name, Object data) throws Exception {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    }

    private void sendUnchecked(SseEmitter emitter, String name, Object data) {
        try {
            send(emitter, name, data);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private ServerSentEvent<Map<String, Object>> event(String name, Map<String, Object> data) {
        return ServerSentEvent.<Map<String, Object>>builder().event(name).data(data).build();
    }

    private String eventName(Map<String, Object> event) {
        Object type = event == null ? null : event.get("type");
        String name = type == null ? "process" : String.valueOf(type).trim();
        return name.isBlank() ? "process" : name;
    }

    public record CreateConversationRequest(String businessCode, String title) { }

    public record SendMessageRequest(String requestId, String input) { }

    public record ChatRequest(String input, String sessionId, String userId, String requestId) { }

    public record MemoryUpsertRequest(Object value, Long ttlSeconds) { }

    public record UpdateProfileRequest(String displayName, String avatarUrl) { }

    private record LegacyConversation(String userId, String externalId, String internalId) { }

    @FunctionalInterface
    private interface DeltaConsumer {
        void accept(String text);
    }
}
