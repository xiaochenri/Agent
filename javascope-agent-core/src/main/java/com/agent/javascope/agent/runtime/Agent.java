package com.agent.javascope.agent.runtime;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.entity.plan.PlanRevisionRecord;
import com.agent.javascope.entity.plan.PlanStepView;
import com.agent.javascope.entity.plan.PlanToolData;
import com.agent.javascope.entity.response.*;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.model.ModelCallException;
import com.agent.javascope.model.ModelRequest;
import com.agent.javascope.model.ModelResult;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public abstract class Agent {

    protected final AgentRuntimeProperties properties;
    protected final AgentPromptProvider promptProvider;
    protected final AgentToolExecutor toolExecutor;
    protected final AgentChatModelClient modelClient;
    protected final AgentJsonCodecUtil json;

    protected String provider;
    protected String model;
    protected String systemInstruction;
    protected List<Map<String, Object>> toolSchemas;
    protected String toolSchemasJson;
    private boolean initialized;

    protected Agent(
            AgentRuntimeProperties properties,
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json) {
        this.properties = properties;
        this.promptProvider = promptProvider;
        this.toolExecutor = toolExecutor;
        this.modelClient = modelClient;
        this.json = json;
    }

    public final void initialize() {
        ensureAgentInitialized();
    }

    protected void ensureAgentInitialized() {
        if (!initialized) {
            initAgent();
        }
    }

    protected JsonNode chatModel(String prompt) {
        return modelContent(modelClient.chat(new ModelRequest(prompt)));
    }

    protected JsonNode chatModelStream(String prompt, Consumer<String> deltaConsumer) {
        return modelContent(modelClient.chatStream(new ModelRequest(prompt), deltaConsumer));
    }

    private JsonNode modelContent(ModelResult result) {
        if (result instanceof ModelResult.Success success) {
            return success.content();
        }
        if (result instanceof ModelResult.Failure failure) {
            throw new ModelCallException(failure.error());
        }
        throw new ModelCallException(null);
    }

    protected AgentExecutionLogEntry buildReasoningLog(
            String input,
            int round,
            String validationFeedback,
            List<Map<String, Object>> businessDecisions,
            List<Map<String, Object>> activeToolFailures,
            Map<String, Object> investigationState,
            Map<String, Object> response) {
        return new AgentExecutionLogEntry(
                "reasoning_round_" + round,
                "reasoning",
                Map.of(
                        "user_input", input,
                        "validation_feedback", validationFeedback,
                        "business_decisions", List.copyOf(businessDecisions),
                        "active_tool_failures", List.copyOf(activeToolFailures),
                        "investigation_state", Map.copyOf(investigationState)),
                response,
                0.6);
    }

    protected AgentExecutionLogEntry buildToolLog(
            int round, String toolName, Map<String, Object> toolInput, Map<String, Object> resultJson) {
        return new AgentExecutionLogEntry(
                "tool_call_round_" + round,
                toolName,
                toolInput,
                resultJson,
                "success".equals(resultJson.get("status")) ? 0.9 : 0.3);
    }

    protected String respondAsText(
            String executionId,
            PlanToolData planResult,
            List<PlanStepView> plan,
            List<AgentExecutionLogEntry> executionLog,
            List<PlanRevisionRecord> revisedPlan,
            List<Map<String, Object>> planLifecycle,
            String blockedReason,
            Map<String, Object> finalAnswer,
            List<String> riskFlags) {
        return json.toJson(buildResponsePayload(
                executionId,
                planResult,
                plan,
                executionLog,
                revisedPlan,
                planLifecycle,
                blockedReason,
                finalAnswer,
                riskFlags));
    }

    protected String respondAsStream(
            String executionId,
            PlanToolData planResult,
            List<PlanStepView> plan,
            List<AgentExecutionLogEntry> executionLog,
            List<PlanRevisionRecord> revisedPlan,
            List<Map<String, Object>> planLifecycle,
            String blockedReason,
            Map<String, Object> finalAnswer,
            List<String> riskFlags,
            Consumer<String> chunkConsumer) {
        Map<String, Object> payload = buildResponsePayload(
                executionId,
                planResult,
                plan,
                executionLog,
                revisedPlan,
                planLifecycle,
                blockedReason,
                finalAnswer,
                riskFlags);
        if (chunkConsumer == null) {
            return json.toJson(payload);
        }
        emitStreamChunk(chunkConsumer, "message", payload.get("message"));
        emitStreamChunk(chunkConsumer, "runtime", payload.get("runtime"));
        emitStreamChunk(chunkConsumer, "metadata", payload.get("metadata"));
        emitStreamChunk(chunkConsumer, "status", payload.get("status"));
        emitStreamChunk(chunkConsumer, "route", payload.get("route"));
        emitStreamChunk(chunkConsumer, "done", true);
        return json.toJson(payload);
    }

    private Map<String, Object> buildResponsePayload(
            String executionId,
            PlanToolData planResult,
            List<PlanStepView> plan,
            List<AgentExecutionLogEntry> executionLog,
            List<PlanRevisionRecord> revisedPlan,
            List<Map<String, Object>> planLifecycle,
            String blockedReason,
            Map<String, Object> finalAnswer,
            List<String> riskFlags) {
        String route = inferRoute(executionLog);

        AgentResponse agentResponse = new AgentResponse();
        agentResponse.setStatus(resolveStatus(blockedReason, finalAnswer));
        agentResponse.setRoute(route);
        agentResponse.setMessage(buildUserMessage(route, finalAnswer));

        AgentRuntimeView runtime = new AgentRuntimeView();
        runtime.setTaskUnderstanding(planResult.getTaskUnderstanding());
        runtime.setPlan(plan);
        runtime.setExecutionLog(executionLog);
        runtime.setRevisedPlan(revisedPlan);
        runtime.setPlanLifecycle(planLifecycle);
        runtime.setBlockedReason(blockedReason);
        runtime.setRiskFlags(riskFlags);
        agentResponse.setRuntime(runtime);

        AgentResponseMetadata metadata = new AgentResponseMetadata();
        metadata.setProvider(provider);
        metadata.setModel(model);
        metadata.setExecutionId(executionId);
        metadata.setCreatedAt(Instant.now().toString());
        agentResponse.setMetadata(metadata);

        return json.parseJson(json.toJson(agentResponse));
    }

    private String resolveStatus(String blockedReason, Map<String, Object> finalAnswer) {
        if (blockedReason != null && !blockedReason.isBlank()) {
            return "blocked";
        }
        if (finalAnswer == null || finalAnswer.isEmpty()) {
            return "failed";
        }
        return "completed";
    }

    private String inferRoute(List<AgentExecutionLogEntry> executionLog) {
        if (executionLog == null || executionLog.isEmpty()) {
            return "task";
        }
        for (AgentExecutionLogEntry entry : executionLog) {
            if (entry == null) {
                continue;
            }
            if ("route_decision".equals(entry.getStep())) {
                String route = json.normalize(String.valueOf(json.asMap(entry.getOutput()).get("route")), "");
                if ("chat".equals(route) || "meta".equals(route) || "task".equals(route)) {
                    return route;
                }
            }
        }
        for (AgentExecutionLogEntry entry : executionLog) {
            if (entry == null) {
                continue;
            }
            if ("direct_reply".equals(entry.getStep()) || "direct_reply_module".equals(entry.getToolName())) {
                return "chat";
            }
        }
        return "task";
    }

    private AgentUserMessage buildUserMessage(String route, Map<String, Object> finalAnswer) {
        AgentUserMessage message = new AgentUserMessage();
        if (finalAnswer == null || finalAnswer.isEmpty()) {
            return message;
        }

        List<String> conclusions = userTextList(finalAnswer.get("core_conclusions"));
        List<String> evidence = userTextList(finalAnswer.get("key_evidence"));
        List<String> risks = userTextList(finalAnswer.get("risk_points"));
        List<String> actions = userTextList(finalAnswer.get("next_actions"));
        boolean directReply = "chat".equals(route) || "meta".equals(route);

        List<AgentMessageSection> sections = new ArrayList<>();
        addSection(sections, "conclusion", "结论", conclusions);
        if (!directReply) {
            addSection(sections, "known_info", "已知信息", evidence);
            addSection(sections, "risk", "需要注意", risks);
        }
        addSection(sections, "next_actions", directReply ? "你可以这样问" : "下一步", actions);

        message.setSections(sections);
        message.setNextActions(actions.stream()
                .map(action -> new AgentNextAction(action, action))
                .toList());
        message.setContent(renderMarkdownContent(directReply, conclusions, evidence, risks, actions));
        return message;
    }

    private List<String> userTextList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = userText(item);
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = userText(value);
        if (!text.isBlank()) {
            result.add(text);
        }
        return result;
    }

    private String userText(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || containsInternalMarker(text)) {
            return "";
        }
        return text;
    }

    private boolean containsInternalMarker(String text) {
        List<String> internalMarkers = List.of(
                "tool_calls",
                "validation",
                "retryable",
                "blocked_reason",
                "risk_flags");
        for (String marker : internalMarkers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private void addSection(List<AgentMessageSection> sections, String type, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sections.add(new AgentMessageSection(type, title, items));
    }

    private String renderMarkdownContent(
            boolean directReply,
            List<String> conclusions,
            List<String> evidence,
            List<String> risks,
            List<String> actions) {
        StringBuilder sb = new StringBuilder();
        appendParagraphs(sb, conclusions);
        if (!directReply) {
            appendListSection(sb, "已知信息", evidence);
            appendListSection(sb, "需要注意", risks);
        }
        appendListSection(sb, directReply ? "你可以这样问" : "下一步", actions);
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

    private void appendListSection(StringBuilder sb, String title, List<String> items) {
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

    private void emitStreamChunk(Consumer<String> chunkConsumer, String event, Object data) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("event", event);
        chunk.put("data", data);
        chunkConsumer.accept(json.toJson(chunk));
    }

    private void initAgent() {
        provider = json.normalize(properties.getProvider(), "openai");
        model = json.normalize(properties.getModel(), "");
        systemInstruction = json.normalize(properties.getSystemInstruction(), "你是通用执行器。");
        toolSchemas = List.copyOf(toolExecutor.listToolSchemas());
        toolSchemasJson = json.toJson(toolSchemas);
        initialized = true;
    }
}
