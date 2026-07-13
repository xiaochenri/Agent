package com.agent.javascope.user.application;

import com.agent.javascope.user.conversation.Conversation;
import com.agent.javascope.user.conversation.ConversationMessage;
import com.agent.javascope.user.infrastructure.JdbcConversationMessageRepository;
import com.agent.javascope.user.infrastructure.JdbcConversationRepository;
import com.agent.javascope.user.infrastructure.JdbcUserRepository;
import com.agent.javascope.user.memory.UserMemoryService;
import com.agent.javascope.user.memory.UserMemoryService.MemoryContext;
import com.agent.javascope.user.port.AgentChatCommand;
import com.agent.javascope.user.port.AgentChatResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class ConversationApplicationService {

    private static final int CONTEXT_MESSAGE_LIMIT = 20;
    private final JdbcUserRepository userRepository;
    private final JdbcConversationRepository conversationRepository;
    private final JdbcConversationMessageRepository messageRepository;
    private final UserMemoryService memoryService;
    private final BusinessAgentRegistry agentRegistry;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Object> conversationLocks = new ConcurrentHashMap<>();

    public ConversationApplicationService(
            JdbcUserRepository userRepository,
            JdbcConversationRepository conversationRepository,
            JdbcConversationMessageRepository messageRepository,
            UserMemoryService memoryService,
            BusinessAgentRegistry agentRegistry,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.memoryService = memoryService;
        this.agentRegistry = agentRegistry;
        this.objectMapper = objectMapper;
    }

    public Conversation create(String userId, String businessCode, String title) {
        return createWithId(userId, UUID.randomUUID().toString(), businessCode, title);
    }

    public Conversation createWithId(String userId, String conversationId, String businessCode, String title) {
        validateId("userId", userId);
        validateId("conversationId", conversationId);
        String normalizedBusiness = normalizeBusiness(businessCode);
        agentRegistry.require(normalizedBusiness);
        userRepository.ensureExists(userId);
        Conversation conversation = conversationRepository.createIfAbsent(
                conversationId, userId, normalizedBusiness, normalizeTitle(title));
        if (!conversation.businessCode().equals(normalizedBusiness)) {
            throw new IllegalArgumentException("conversation businessCode cannot be changed");
        }
        return conversation;
    }

    public List<Conversation> list(String userId, String businessCode, int limit) {
        String normalizedBusiness = businessCode == null || businessCode.isBlank()
                ? null : normalizeBusiness(businessCode);
        return conversationRepository.listOwned(userId, normalizedBusiness, clamp(limit, 1, 100));
    }

    public Conversation get(String userId, String conversationId) {
        return conversationRepository.findOwned(conversationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("conversation not found"));
    }

    public List<ConversationMessage> messages(String userId, String conversationId, long beforeId, int limit) {
        get(userId, conversationId);
        return messageRepository.list(conversationId, beforeId, clamp(limit, 1, 100));
    }

    public void archive(String userId, String conversationId) {
        get(userId, conversationId);
        conversationRepository.archive(conversationId, userId);
    }

    public ChatResponse chat(String userId, String conversationId, String requestId, String input) {
        return executeChat(userId, conversationId, requestId, input, null);
    }

    public ChatResponse chatStream(
            String userId,
            String conversationId,
            String requestId,
            String input,
            Consumer<Map<String, Object>> eventConsumer) {
        return executeChat(userId, conversationId, requestId, input, eventConsumer);
    }

    private ChatResponse executeChat(
            String userId,
            String conversationId,
            String requestId,
            String input,
            Consumer<Map<String, Object>> eventConsumer) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        String normalizedRequestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.trim();
        validateId("requestId", normalizedRequestId);

        Object lock = conversationLocks.computeIfAbsent(conversationId, ignored -> new Object());
        synchronized (lock) {
            Conversation conversation = get(userId, conversationId);
            if (!"active".equals(conversation.status())) {
                throw new IllegalArgumentException("conversation is not active");
            }
            var cached = messageRepository.findCompletedAssistant(conversationId, normalizedRequestId);
            if (cached.isPresent()) {
                ConversationMessage message = cached.get();
                emit(eventConsumer, Map.of(
                        "type", "process",
                        "message", "命中 requestId 幂等结果，复用已完成回答"));
                return new ChatResponse(conversationId, normalizedRequestId, message.content(),
                        message.executionId(), parseMetadata(message.metadata()), true);
            }

            List<ConversationMessage> history = messageRepository.recent(conversationId, CONTEXT_MESSAGE_LIMIT);
            messageRepository.insert(UUID.randomUUID().toString(), conversationId, userId, null,
                    "user", input.trim(), "completed", null, "{}");

            AgentChatResult result;
            try {
                var memoryReply = memoryService.tryWriteCommand(
                        userId, conversation.businessCode(), input.trim());
                if (memoryReply.isPresent()) {
                    emit(eventConsumer, Map.of(
                            "type", "process", "message", "已识别并写入用户记忆"));
                    result = new AgentChatResult(memoryReply.get(), null, Map.of("type", "memory_write"));
                } else {
                    MemoryContext memory = memoryService.loadContext(userId, conversation.businessCode());
                    AgentChatCommand command = new AgentChatCommand(
                            normalizedRequestId, userId, conversationId, input.trim(), history,
                            memory.global(), memory.business());
                    result = eventConsumer == null
                            ? agentRegistry.require(conversation.businessCode()).chat(command)
                            : agentRegistry.require(conversation.businessCode()).chatStream(command, eventConsumer);
                }
            } catch (RuntimeException error) {
                messageRepository.insert(UUID.randomUUID().toString(), conversationId, userId, null,
                        "assistant", "", "failed", null,
                        toJson(Map.of("error", error.getClass().getSimpleName())));
                throw error;
            }

            String metadata = toJson(compactPayload(result.payload()));
            messageRepository.insert(UUID.randomUUID().toString(), conversationId, userId, normalizedRequestId,
                    "assistant", result.reply(), "completed", result.executionId(), metadata);
            conversationRepository.touch(conversationId, abbreviate(input.trim(), 80));
            return new ChatResponse(conversationId, normalizedRequestId, result.reply(),
                    result.executionId(), result.payload(), false);
        }
    }

    private void emit(Consumer<Map<String, Object>> consumer, Map<String, Object> event) {
        if (consumer != null) {
            consumer.accept(event);
        }
    }

    private Map<String, Object> parseMetadata(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("message metadata cannot be serialized", e);
        }
    }

    private Map<String, Object> compactPayload(Map<String, Object> payload) {
        java.util.LinkedHashMap<String, Object> compact = new java.util.LinkedHashMap<>();
        for (String key : List.of("message", "metadata", "status", "route")) {
            if (payload.containsKey(key)) {
                compact.put(key, payload.get(key));
            }
        }
        return compact;
    }

    private String normalizeBusiness(String value) {
        return value == null || value.isBlank() ? "stock" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeTitle(String title) {
        return title == null || title.isBlank() ? null : abbreviate(title.trim(), 255);
    }

    private String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void validateId(String name, String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException(name + " must contain 1-64 characters");
        }
    }

    public record ChatResponse(
            String conversationId,
            String requestId,
            String reply,
            String executionId,
            Map<String, Object> payload,
            boolean cached) {
    }
}
