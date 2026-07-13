package com.agent.javascope.user.port;

import java.util.Map;
import java.util.function.Consumer;

public interface BusinessAgentHandler {

    String businessCode();

    AgentChatResult chat(AgentChatCommand command);

    /** Streams business execution events while keeping the final result contract unchanged. */
    default AgentChatResult chatStream(
            AgentChatCommand command, Consumer<Map<String, Object>> eventConsumer) {
        return chat(command);
    }
}
