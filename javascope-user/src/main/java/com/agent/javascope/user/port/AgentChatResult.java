package com.agent.javascope.user.port;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record AgentChatResult(
        String reply,
        String executionId,
        Map<String, Object> payload) {

    public AgentChatResult {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
