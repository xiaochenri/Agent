package com.agent.javascope.tools;

import java.util.Map;
import java.util.List;

public interface AgentToolExecutor {

    String execute(String tool, Map<String, Object> input, String rawInput);

    List<Map<String, Object>> listToolSchemas();
}
