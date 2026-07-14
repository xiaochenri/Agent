package com.agent.javascope.agent.routing;

import com.agent.javascope.agent.runtime.RuntimeState;
import com.agent.javascope.context.trace.ExecutionEventType;
import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.entity.execution.AgentToolCall;
import com.agent.javascope.entity.routing.RouteDecision;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.model.ModelCallException;
import com.agent.javascope.model.ModelRequest;
import com.agent.javascope.model.ModelResult;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 负责输入前置路由：区分任务、闲聊和元问题，并处理非任务场景的直接回复。
 */
public class InputRouter {

    /** 构建路由 prompt 和直答 prompt。 */
    private final AgentPromptProvider promptProvider;
    /** 实际调用大模型的客户端。 */
    private final AgentChatModelClient modelClient;
    /** JSON 解析与容错转换工具。 */
    private final AgentJsonCodecUtil json;
    /** 用于阻止直答分支误触发工具调用。 */
    private final AgentToolCallExtractor toolCallExtractor;

    public InputRouter(
            AgentPromptProvider promptProvider,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json,
            AgentToolCallExtractor toolCallExtractor) {
        this.promptProvider = promptProvider;
        this.modelClient = modelClient;
        this.json = json;
        this.toolCallExtractor = toolCallExtractor;
    }

    /**
     * 调用模型完成意图路由，并把路由结果写入执行日志。
     */
    public RouteDecision route(String input, RuntimeState state, String systemInstruction) {
        String prompt = promptProvider.buildRoutePrompt(systemInstruction, input);
        state.trace.record(ExecutionEventType.ROUTE_MODEL_REQUESTED, Map.of("prompt", prompt), Map.of());
        Map<String, Object> raw = json.asMap(modelContent(modelClient.chat(new ModelRequest(prompt))));
        state.trace.record(ExecutionEventType.ROUTE_MODEL_RESPONDED, Map.of(), raw);
        return handleRouteModelResponse(input, state, raw);
    }

    public RouteDecision routeStream(
            String input, RuntimeState state, String systemInstruction, Consumer<String> deltaConsumer) {
        String prompt = promptProvider.buildRoutePrompt(systemInstruction, input);
        state.trace.record(ExecutionEventType.ROUTE_MODEL_REQUESTED, Map.of("prompt", prompt, "stream", true), Map.of());
        Map<String, Object> raw = json.asMap(modelContent(modelClient.chatStream(new ModelRequest(prompt), deltaConsumer)));
        state.trace.record(ExecutionEventType.ROUTE_MODEL_RESPONDED, Map.of("stream", true), raw);
        return handleRouteModelResponse(input, state, raw);
    }

    private RouteDecision handleRouteModelResponse(String input, RuntimeState state, Map<String, Object> raw) {
        String route = normalizeRoute(raw.get("route"));
        String executionMode = normalizeExecutionMode(route, raw.get("execution_mode"));
        double confidence = normalizeConfidence(raw.get("confidence"));
        String reason = json.normalize(raw.get("reason") == null ? "" : String.valueOf(raw.get("reason")), "");
        String rawRoute = raw.get("route") == null ? "" : String.valueOf(raw.get("route"));
        if (!route.equals(json.normalize(rawRoute, "").toLowerCase())) {
            state.riskFlags.add("route_normalized_to_" + route);
        }
        if (confidence < 0.5) {
            state.riskFlags.add("route_low_confidence");
        }
        RouteDecision decision = new RouteDecision(route, executionMode, confidence, reason);
        Map<String, Object> routeOutput = new LinkedHashMap<>();
        routeOutput.put("route", route);
        routeOutput.put("execution_mode", executionMode);
        routeOutput.put("confidence", confidence);
        routeOutput.put("reason", reason);
        state.executionLog.add(new AgentExecutionLogEntry(
                "route_decision",
                "intent_router",
                Map.of("user_input", input),
                routeOutput,
                confidence));
        return decision;
    }

    /**
     * 对 chat/meta 分支生成最终回复；如果模型仍返回工具调用，则记录风险并丢弃工具调用。
     */
    public Map<String, Object> buildDirectRouteFinalAnswer(
            String input, RouteDecision routeDecision, RuntimeState state, String systemInstruction) {
        String prompt = promptProvider.buildDirectReplyPrompt(
                systemInstruction,
                input,
                routeDecision.getRoute(),
                routeDecision.getReason());
        state.trace.record(ExecutionEventType.ACTION_MODEL_REQUESTED, Map.of("prompt", prompt, "route", routeDecision.getRoute()), Map.of());
        Map<String, Object> response = json.asMap(modelContent(modelClient.chat(new ModelRequest(prompt))));
        state.trace.record(ExecutionEventType.ACTION_MODEL_RESPONDED, Map.of("route", routeDecision.getRoute()), response);
        return handleDirectReplyModelResponse(input, routeDecision, state, response);
    }

    public Map<String, Object> buildDirectRouteFinalAnswerStream(
            String input,
            RouteDecision routeDecision,
            RuntimeState state,
            String systemInstruction,
            Consumer<String> deltaConsumer) {
        String prompt = promptProvider.buildDirectReplyPrompt(
                systemInstruction,
                input,
                routeDecision.getRoute(),
                routeDecision.getReason());
        state.trace.record(ExecutionEventType.ACTION_MODEL_REQUESTED, Map.of("prompt", prompt, "route", routeDecision.getRoute(), "stream", true), Map.of());
        Map<String, Object> response = json.asMap(modelContent(modelClient.chatStream(new ModelRequest(prompt), deltaConsumer)));
        state.trace.record(ExecutionEventType.ACTION_MODEL_RESPONDED, Map.of("route", routeDecision.getRoute(), "stream", true), response);
        return handleDirectReplyModelResponse(input, routeDecision, state, response);
    }

    private Map<String, Object> handleDirectReplyModelResponse(
            String input, RouteDecision routeDecision, RuntimeState state, Map<String, Object> response) {
        state.executionLog.add(new AgentExecutionLogEntry(
                "direct_reply",
                "direct_reply_module",
                Map.of(
                        "route", routeDecision.getRoute(),
                        "route_reason", routeDecision.getReason(),
                        "user_input", input),
                response,
                0.7));
        List<AgentToolCall> toolCalls = toolCallExtractor.extract(response);
        if (!toolCalls.isEmpty()) {
            state.riskFlags.add("direct_reply_tool_call_blocked");
        }
        Map<String, Object> finalAnswer = json.asMap(response.get("final_answer"));
        if (finalAnswer == null || finalAnswer.isEmpty()) {
            return buildDirectRouteFinalAnswerFallback(routeDecision);
        }
        return finalAnswer;
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

    /**
     * 将模型返回的置信度收敛到 0~1；无法解析时使用中性值 0.5。
     */
    private double normalizeConfidence(Object confidenceObj) {
        if (confidenceObj instanceof Number number) {
            double value = number.doubleValue();
            return Math.max(0.0, Math.min(1.0, value));
        }
        if (confidenceObj instanceof String text) {
            try {
                double value = Double.parseDouble(text.trim());
                return Math.max(0.0, Math.min(1.0, value));
            } catch (NumberFormatException ignored) {
                return 0.5;
            }
        }
        return 0.5;
    }

    /**
     * 只允许 chat/meta/task 三种路由，未知值保守归入 task。
     */
    private String normalizeRoute(Object routeObj) {
        String normalized = json.normalize(routeObj == null ? "" : String.valueOf(routeObj), "task")
                .toLowerCase();
        if ("chat".equals(normalized) || "meta".equals(normalized) || "task".equals(normalized)) {
            return normalized;
        }
        return "task";
    }

    /** 非任务不进入执行链；任务仅接受 direct/react/planned，未知值保守要求先规划。 */
    private String normalizeExecutionMode(String route, Object executionModeObj) {
        if (!"task".equals(route)) {
            return "none";
        }
        String normalized = json.normalize(
                executionModeObj == null ? "" : String.valueOf(executionModeObj), "planned").toLowerCase();
        if ("direct".equals(normalized) || "react".equals(normalized) || "planned".equals(normalized)) {
            return normalized;
        }
        return "planned";
    }

    /**
     * 直答模型未按协议返回 final_answer 时的兜底结构。
     */
    private Map<String, Object> buildDirectRouteFinalAnswerFallback(RouteDecision routeDecision) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        String route = routeDecision.getRoute();
        if ("meta".equals(route)) {
            fallback.put("core_conclusions", List.of("我是任务型助手，可协助分析、检索、规划并给出结构化结论。"));
            fallback.put("key_evidence", List.of("当前问题被识别为 meta 问题，按直答模式返回。"));
            fallback.put("risk_points", List.of("未进入工具链，回复基于通用能力说明。"));
            fallback.put("next_actions", List.of("你可以直接给出任务目标和必要约束，我将继续处理。"));
        } else {
            fallback.put("core_conclusions", List.of("收到你的消息。"));
            fallback.put("key_evidence", List.of("当前问题被识别为 chat 闲聊场景，按直答模式返回。"));
            fallback.put("risk_points", List.of("未进入任务工具链，不涉及外部检索结果。"));
            fallback.put("next_actions", List.of("如需我执行任务，请直接描述目标和约束条件。"));
        }
        fallback.put("route", route);
        fallback.put("fallback", true);
        return fallback;
    }
}
