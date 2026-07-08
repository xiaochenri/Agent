package com.agent.javascope.agent;

import com.agent.javascope.chat.AgentChatModelClient;
import com.agent.javascope.config.AgentRuntimeProperties;
import com.agent.javascope.entity.AgentExecutionLogEntry;
import com.agent.javascope.entity.PlanRevisionRecord;
import com.agent.javascope.entity.PlanStepView;
import com.agent.javascope.entity.PlanToolData;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.tools.AgentToolExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.agent.javascope.util.AgentJsonCodecUtil;
import org.springframework.beans.factory.SmartInitializingSingleton;

public abstract class Agent implements SmartInitializingSingleton {

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

    @Override
    public void afterSingletonsInstantiated() {
        ensureAgentInitialized();
    }

    protected void ensureAgentInitialized() {
        if (!initialized) {
            initAgent();
        }
    }

    protected String chatModel(String prompt) {
        return modelClient.chat(prompt);
    }

    protected AgentExecutionLogEntry buildReasoningLog(
            String input, int round, String validationFeedback, Map<String, Object> response) {
        return new AgentExecutionLogEntry(
                "reasoning_round_" + round,
                "reasoning",
                Map.of("user_input", input, "validation_feedback", validationFeedback),
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
            PlanToolData planResult,
            List<PlanStepView> plan,
            List<AgentExecutionLogEntry> executionLog,
            List<PlanRevisionRecord> revisedPlan,
            List<Map<String, Object>> planLifecycle,
            String blockedReason,
            Map<String, Object> finalAnswer,
            List<String> riskFlags) {
        return json.toJson(buildResponsePayload(
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
        emitStreamChunk(chunkConsumer, "task_understanding", payload.get("task_understanding"));
        emitStreamChunk(chunkConsumer, "plan", payload.get("plan"));
        emitStreamChunk(chunkConsumer, "execution_log", payload.get("execution_log"));
        emitStreamChunk(chunkConsumer, "revised_plan", payload.get("revised_plan"));
        emitStreamChunk(chunkConsumer, "plan_lifecycle", payload.get("plan_lifecycle"));
        emitStreamChunk(chunkConsumer, "blocked_reason", payload.get("blocked_reason"));
        emitStreamChunk(chunkConsumer, "final_answer", payload.get("final_answer"));
        emitStreamChunk(chunkConsumer, "risk_flags", payload.get("risk_flags"));
        emitStreamChunk(chunkConsumer, "done", true);
        return json.toJson(payload);
    }

    private Map<String, Object> buildResponsePayload(
            PlanToolData planResult,
            List<PlanStepView> plan,
            List<AgentExecutionLogEntry> executionLog,
            List<PlanRevisionRecord> revisedPlan,
            List<Map<String, Object>> planLifecycle,
            String blockedReason,
            Map<String, Object> finalAnswer,
            List<String> riskFlags) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("task_understanding", planResult.getTaskUnderstanding());
        response.put("plan", plan);
        response.put("execution_log", executionLog);
        response.put("revised_plan", revisedPlan);
        response.put("plan_lifecycle", planLifecycle);
        response.put("blocked_reason", blockedReason);
        response.put("final_answer", finalAnswer);
        response.put("risk_flags", riskFlags);
        return response;
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
