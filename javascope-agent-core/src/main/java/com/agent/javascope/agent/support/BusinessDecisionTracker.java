package com.agent.javascope.agent.support;

import com.agent.javascope.agent.runtime.RuntimeState;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maintains optional, domain-neutral decision contracts returned by business tools. */
public final class BusinessDecisionTracker {

    private BusinessDecisionTracker() {}

    /**
     * Captures {@code data.decision} when present. This has no control-flow effect: it only
     * makes a business result visible as high-priority context in the following reasoning turn.
     */
    public static void capture(String toolName, Map<String, Object> resultJson, RuntimeState state) {
        Object data = resultJson.get("data");
        if (!(data instanceof Map<?, ?> dataMap) || !(dataMap.get("decision") instanceof Map<?, ?> rawDecision)) {
            return;
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        rawDecision.forEach((key, value) -> decision.put(String.valueOf(key), value));
        if (decision.isEmpty()) {
            return;
        }
        if (dataMap.get("answer_context") instanceof Map<?, ?> rawAnswerContext) {
            Map<String, Object> answerContext = new LinkedHashMap<>();
            rawAnswerContext.forEach((key, value) -> answerContext.put(String.valueOf(key), value));
            decision.put("answer_context", answerContext);
        }
        decision.putIfAbsent("source_tool", toolName);
        String decisionKey = String.valueOf(decision.getOrDefault("decision_key", toolName));
        decision.put("decision_key", decisionKey);
        state.businessDecisions.removeIf(existing -> decisionKey.equals(String.valueOf(existing.get("decision_key"))));
        state.businessDecisions.add(decision);
    }
}
