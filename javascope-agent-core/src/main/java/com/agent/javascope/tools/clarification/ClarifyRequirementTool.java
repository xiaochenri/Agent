package com.agent.javascope.tools.clarification;

import com.agent.javascope.tool.error.DefaultToolErrorClassifier;
import com.agent.javascope.tool.middleware.ToolResultFactory;
import com.agent.javascope.tool.runtime.ToolErrorCode;
import com.agent.javascope.tool.runtime.ClarificationBusinessProvider;
import com.agent.javascope.tool.annotation.AgentTool;
import com.agent.javascope.tool.annotation.ToolType;
import com.agent.javascope.tool.annotation.ToolVisibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClarifyRequirementTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 业务扩展点，用于提供领域候选项、默认值、补充问题和高风险确认策略。 */
    private final ClarificationBusinessProvider businessProvider;

    public ClarifyRequirementTool(ClarificationBusinessProvider businessProvider) {
        this.businessProvider = businessProvider == null ? new ClarificationBusinessProvider() {
        } : businessProvider;
    }

    @AgentTool(
            name = "clarify_requirement",
            title = "需求澄清",
            description = "通用业务语义澄清工具。"
                    + " 仅用于缺少完成目标必需的业务信息、存在实质不同的业务解释或需要用户授权时。"
                    + " 不处理参数抽取、格式/编码/schema 适配、工具失败或工具能力限制。"
                    + " 非适用场景：身份问答、能力介绍、闲聊问候。"
                    + " 输出结构化决策：action/reasoning/slots/clarification_question/default_assumption/suggested_options。",
            namespace = "system.clarification",
            category = "clarification",
            tags = {"system", "clarification"},
            toolType = ToolType.SYSTEM,
            visibility = ToolVisibility.MODEL_VISIBLE,
            readOnly = true,
            idempotent = false,
            allowedDirectCall = true,
            allowedInPlanStep = false,
            inputSchema = """
                    {
                      "type": "object",
                        "properties": {
                        "phase": {"type": "string", "enum": ["initial", "runtime"], "description": "initial=第一轮澄清；runtime=工具观察后产生的执行中澄清。"},
                        "user_input": {"type": "string", "description": "用户原始输入，可省略，省略时 runtime 使用本轮原始输入。"},
                        "working_memory": {"type": "string", "description": "本轮可用于澄清的短期记忆。"},
                        "long_term_memory": {"type": "string", "description": "用户长期记忆或画像。"},
                        "user_profile": {"type": "string", "description": "用户画像，long_term_memory 的兼容字段。"},
                        "reason": {"type": "string", "description": "runtime 澄清必填：工具观察后为什么无法安全继续。"},
                        "blocking_decision": {"type": "string", "description": "runtime 澄清必填：必须由用户做出的选择或授权。"},
                        "clarification_kind": {"type": "string", "enum": ["missing_business_information", "semantic_ambiguity", "authorization"], "description": "澄清原因只能是缺少业务信息、实质语义歧义或用户授权；不得填写参数/schema/工具问题。"},
                        "materially_different_outcomes": {"type": "boolean", "description": "缺失信息或不同选择是否会实质改变任务结果、对象或外部影响。ask 必须为 true。"},
                        "requires_user_choice": {"type": "boolean", "description": "该阻塞是否只能由用户选择解决。"},
                        "confirmation_required": {"type": "boolean", "description": "后续动作是否涉及副作用并需要用户确认。"},
                        "missing_slots": {"type": "array", "items": {"type": "object"}, "description": "用户尚未表达且完成目标必需的业务语义，支持 name/label/priority/reason/candidates/default_value；禁止填写仅供下游工具使用的参数字段。"},
                        "observed_candidates": {"type": "array", "items": {"type": "string"}, "description": "工具观察得到、会导致实质不同业务结果的候选口径或对象；同义格式不得作为不同候选。"},
                        "outcome_impacts": {"type": "array", "items": {"type": "string"}, "description": "semantic_ambiguity 必填：逐项说明至少两个候选如何产生不同的业务结果、对象或外部影响。"},
                        "evidence_refs": {"type": "array", "items": {"type": "string"}, "description": "支撑运行时澄清的执行日志引用。"}
                      },
                      "required": []
                    }
                    """)
    public String clarifyRequirement(Map<String, Object> input, String rawInput) {
        // 用户原始输入优先于模型提供的 user_input，避免模型通过工具参数伪造已经存在的 P0 对象。
        String userInput = firstNonBlank(rawInput, stringValue(input.get("user_input")));
        String normalized = currentUserTurn(userInput);
        String workingMemory = stringValue(input.get("working_memory"));
        String longTermMemory = firstNonBlank(stringValue(input.get("long_term_memory")), stringValue(input.get("user_profile")));
        ClarificationPhase phase = ClarificationPhase.from(input.get("phase"));
        String runtimeReason = stringValue(input.get("reason")).trim();
        String blockingDecision = stringValue(input.get("blocking_decision")).trim();
        String clarificationKind = stringValue(input.get("clarification_kind")).trim();
        boolean materiallyDifferentOutcomes = booleanValue(input.get("materially_different_outcomes"), false);
        boolean requiresUserChoice = booleanValue(input.get("requires_user_choice"), !blockingDecision.isBlank());
        boolean confirmationRequired = booleanValue(input.get("confirmation_required"), false);
        List<String> observedCandidates = stringList(input.get("observed_candidates"));
        List<String> outcomeImpacts = stringList(input.get("outcome_impacts"));
        List<String> evidenceRefs = stringList(input.get("evidence_refs"));
        List<Map<String, Object>> declaredSlots = mapList(input.get("missing_slots"));

        String semanticPolicyError = validateSemanticBoundary(
                phase,
                clarificationKind,
                materiallyDifferentOutcomes,
                requiresUserChoice,
                confirmationRequired,
                blockingDecision,
                observedCandidates,
                outcomeImpacts,
                declaredSlots);
        if (!semanticPolicyError.isBlank()) {
            return fail("clarify_requirement", ToolErrorCode.CLARIFICATION_POLICY_REJECTED,
                    semanticPolicyError, false);
        }

        // Core 只处理模型声明的抽象槽位，不内置任何领域槽位或关键词识别。
        List<Map<String, Object>> slots = phase == ClarificationPhase.RUNTIME
                ? runtimeSlots(blockingDecision, observedCandidates)
                : normalizeDeclaredSlots(normalized, longTermMemory, declaredSlots);
        List<String> missingFields = new ArrayList<>();
        for (Map<String, Object> slot : slots) {
            String label = firstNonBlank(stringValue(slot.get("label")), stringValue(slot.get("name")));
            if (!label.isBlank()) {
                missingFields.add(label);
            }
        }
        boolean needsConfirmation = businessProvider.confirmBeforeAction(normalized, missingFields);

        Map<String, Object> recognizedFields = new LinkedHashMap<>();
        for (Map<String, Object> slot : slots) {
            recognizedFields.put(stringValue(slot.get("name")), !"missing".equals(slot.get("status")));
        }
        List<String> capabilities = businessProvider.capabilities(normalized);
        List<String> businessSuggestedReplies = businessProvider.suggestedReplies(normalized);
        List<String> extraQuestions = businessProvider.extraQuestions(normalized, missingFields);
        String nextStepHint = businessProvider.nextStepHint(normalized, missingFields);

        ClarificationDecision decisionResult;
        if (confirmationRequired || needsConfirmation) {
            decisionResult = new ClarificationDecision(
                    "confirm_before_action",
                    firstNonBlank(runtimeReason, "后续动作包含副作用，执行前必须获得用户确认。"),
                    "P0",
                    true);
        } else if (requiresUserChoice && (!blockingDecision.isBlank() || !slots.isEmpty())) {
            // 模型已经确认该缺口必须由用户决定时，工具只校验并标准化，不能再次把 ask 降级为 direct。
            decisionResult = new ClarificationDecision(
                    "ask",
                    firstNonBlank(runtimeReason, "存在必须由用户补充的关键执行条件。"),
                    "P0",
                    true);
        } else if (phase == ClarificationPhase.RUNTIME) {
            decisionResult = decideRuntimeClarification(runtimeReason, blockingDecision, false, false);
        } else if (hasPriority(slots, "P0")) {
            decisionResult = new ClarificationDecision(
                    "ask",
                    firstNonBlank(runtimeReason, "存在必须由用户补充的关键执行条件。"),
                    "P0",
                    true);
        } else if (!slots.isEmpty()) {
            decisionResult = new ClarificationDecision(
                    "execute_with_guess",
                    "非关键槽位缺失，可使用领域默认值继续执行。",
                    "P1",
                    false);
        } else {
            decisionResult = new ClarificationDecision(
                    "direct_execute",
                    "未声明需要澄清的关键槽位，继续执行。",
                    "P2",
                    false);
        }
        String action = decisionResult.action();
        String reasoning = decisionResult.reasoning();
        String clarificationQuestion = "";
        String defaultAssumption = "";
        List<String> suggestedOptions;

        if ("ask".equals(action)) {
            suggestedOptions = !observedCandidates.isEmpty()
                    ? formatRuntimeCandidates(observedCandidates)
                    : optionsFromSlots(slots);
            clarificationQuestion = phase == ClarificationPhase.RUNTIME
                    ? buildRuntimeQuestion(blockingDecision, suggestedOptions)
                    : buildStructuredQuestion(suggestedOptions, extraQuestions);
        } else if ("confirm_before_action".equals(action)) {
            suggestedOptions = List.of("【A】确认执行", "【B】取消", "【C】修改请求");
            clarificationQuestion = buildStructuredQuestion(suggestedOptions, extraQuestions);
        } else if ("execute_with_guess".equals(action)) {
            Map<String, Object> slot = firstSlot(slots);
            String assumedValue = stringValue(slot.get("default_value"));
            defaultAssumption = assumedValue.isBlank()
                    ? "将使用领域层的安全默认值继续执行。"
                    : "默认使用" + assumedValue + "，如需可随时调整。";
            suggestedOptions = optionsFromSlots(slots);
        } else {
            defaultAssumption = "";
            suggestedOptions = List.of();
        }

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", action);
        decision.put("reasoning", reasoning);
        decision.put("priority", decisionResult.priority());
        decision.put("requires_user_response", decisionResult.requiresUserResponse());
        decision.put("default_assumption", defaultAssumption);

        // pending_clarification 供后续多轮恢复使用：用户回复后可按 id 合并槽位继续执行。
        Map<String, Object> pendingClarification = new LinkedHashMap<>();
        pendingClarification.put("id", "clarify_" + UUID.randomUUID());
        pendingClarification.put("original_user_input", normalized);
        pendingClarification.put("missing_slots", missingFields);
        pendingClarification.put("slots", slots);
        pendingClarification.put("suggested_options", suggestedOptions);
        pendingClarification.put("default_assumption", defaultAssumption);
        pendingClarification.put("resume_policy", "用户回复选项、补充槽位值或确认动作后，合并 original_user_input 继续 route/plan。");
        pendingClarification.put("expires_after_turns", 3);

        Map<String, Object> businessContext = new LinkedHashMap<>();
        businessContext.put("capabilities", capabilities);
        businessContext.put("suggested_replies", businessSuggestedReplies);
        businessContext.put("extra_questions", extraQuestions);
        businessContext.put("next_step_hint", nextStepHint);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_input", normalized);
        data.put("phase", phase.value());
        data.put("runtime_reason", runtimeReason);
        data.put("blocking_decision", blockingDecision);
        data.put("clarification_kind", clarificationKind);
        data.put("materially_different_outcomes", materiallyDifferentOutcomes);
        data.put("outcome_impacts", outcomeImpacts);
        data.put("evidence_refs", evidenceRefs);
        data.put("missing_fields", missingFields);
        data.put("recognized_fields", recognizedFields);
        data.put("slots", slots);
        data.put("decision", decision);
        data.put("business_context", businessContext);
        data.put("pending_clarification", pendingClarification);
        data.put("memory_snapshot", Map.of(
                "working_memory", workingMemory,
                "long_term_memory", longTermMemory));
        data.put("action", action);
        data.put("reasoning", reasoning);
        data.put("clarification_question", clarificationQuestion);
        data.put("default_assumption", defaultAssumption);
        data.put("suggested_options", suggestedOptions);
        return success(
                "clarify_requirement",
                data,
                List.of(
                        "action 必须是 ask/execute_with_guess/direct_execute/confirm_before_action",
                        "action=ask 时 clarification_question 非空",
                        "action=confirm_before_action 时必须等待用户确认",
                        "ask 只能用于缺少业务信息、实质语义歧义或用户授权",
                        "参数/schema/格式/编码/工具问题不得触发澄清",
                        "slots 必须描述关键槽位状态",
                        "action=ask/confirm_before_action 时 suggested_options 至少包含2项"));
    }

    /**
     * 在工具边界阻止模型把参数适配或执行故障伪装成用户需求缺失。
     * Core 不解释具体领域语义，但要求模型明确声明业务原因和结果差异。
     */
    private String validateSemanticBoundary(
            ClarificationPhase phase,
            String clarificationKind,
            boolean materiallyDifferentOutcomes,
            boolean requiresUserChoice,
            boolean confirmationRequired,
            String blockingDecision,
            List<String> observedCandidates,
            List<String> outcomeImpacts,
            List<Map<String, Object>> declaredSlots) {
        boolean intendsToPause = confirmationRequired
                || requiresUserChoice
                || declaredSlots.stream().anyMatch(slot ->
                        "P0".equalsIgnoreCase(stringValue(slot.get("priority"))));
        if (!intendsToPause) {
            return "";
        }
        if (!List.of("missing_business_information", "semantic_ambiguity", "authorization")
                .contains(clarificationKind)) {
            return "澄清原因必须是缺少业务信息、实质语义歧义或用户授权；参数抽取、格式/schema 适配和工具问题不得触发澄清";
        }
        if (!materiallyDifferentOutcomes) {
            return "必须说明缺失信息或不同选择会实质改变任务结果；同义表达、格式、编码或可确定映射不得澄清";
        }
        if ("authorization".equals(clarificationKind)) {
            return "";
        }
        if (blockingDecision.isBlank()) {
            return "必须用业务语言说明需要用户决定的事项，不能只提供工具参数名";
        }
        if ("semantic_ambiguity".equals(clarificationKind) && outcomeImpacts.size() < 2) {
            return "语义歧义必须说明至少两个候选会如何产生不同的业务结果；同义格式不能作为不同影响";
        }
        if (phase == ClarificationPhase.RUNTIME
                && "semantic_ambiguity".equals(clarificationKind)
                && observedCandidates.size() < 2) {
            return "运行时语义歧义必须提供至少两个会产生实质不同业务结果的候选项";
        }
        return "";
    }

    /**
     * 执行中澄清只允许处理“必须由用户决定”的阻塞；普通工具失败和证据不足应继续 ReAct。
     */
    private ClarificationDecision decideRuntimeClarification(
            String reason,
            String blockingDecision,
            boolean requiresUserChoice,
            boolean confirmationRequired) {
        if (confirmationRequired) {
            return new ClarificationDecision(
                    "confirm_before_action",
                    firstNonBlank(reason, "后续动作包含副作用，执行前必须获得用户确认。"),
                    "P0",
                    true);
        }
        if (requiresUserChoice && !blockingDecision.isBlank()) {
            return new ClarificationDecision(
                    "ask",
                    firstNonBlank(reason, "工具观察产生了必须由用户选择的关键分支。"),
                    "P0",
                    true);
        }
        return new ClarificationDecision(
                "direct_execute",
                "当前运行时不确定性仍可通过安全工具或默认策略解决，不应中断用户。",
                "P2",
                false);
    }

    /** 规整模型声明的抽象槽位；领域候选值和默认值只能由扩展点提供。 */
    private List<Map<String, Object>> normalizeDeclaredSlots(
            String userInput, String memory, List<Map<String, Object>> declaredSlots) {
        List<Map<String, Object>> normalizedSlots = new ArrayList<>();
        for (Map<String, Object> source : declaredSlots) {
            Map<String, Object> slot = new LinkedHashMap<>(source);
            String name = stringValue(source.get("name")).trim();
            if (name.isBlank()) {
                continue;
            }
            slot.put("name", name);
            slot.put("label", firstNonBlank(stringValue(source.get("label")), name));
            slot.put("status", "missing");
            slot.putIfAbsent("priority", "P0");
            slot.putIfAbsent("value", null);
            String defaultValue = firstNonBlank(
                    stringValue(source.get("default_value")),
                    businessProvider.defaultValue(userInput, name, memory));
            slot.put("default_value", defaultValue);
            List<String> candidates = stringList(source.get("candidates"));
            if (candidates.isEmpty()) {
                candidates = businessProvider.slotCandidates(userInput, name);
            }
            slot.put("candidates", candidates == null ? List.of() : candidates);
            normalizedSlots.add(slot);
        }
        return normalizedSlots;
    }

    /** 运行时只保留当前阻塞决策，不推测任何领域槽位。 */
    private List<Map<String, Object>> runtimeSlots(String blockingDecision, List<String> candidates) {
        if (blockingDecision == null || blockingDecision.isBlank()) {
            return List.of();
        }
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("name", "runtime_decision");
        slot.put("label", blockingDecision);
        slot.put("status", "missing");
        slot.put("priority", "P0");
        slot.put("value", null);
        slot.put("default_value", "");
        slot.put("candidates", candidates == null ? List.of() : candidates);
        return List.of(slot);
    }

    private boolean hasPriority(List<Map<String, Object>> slots, String priority) {
        return slots.stream().anyMatch(slot -> priority.equalsIgnoreCase(stringValue(slot.get("priority"))));
    }

    private Map<String, Object> firstSlot(List<Map<String, Object>> slots) {
        return slots.isEmpty() ? Map.of() : slots.get(0);
    }

    private List<String> optionsFromSlots(List<Map<String, Object>> slots) {
        List<String> candidates = new ArrayList<>();
        for (Map<String, Object> slot : slots) {
            candidates.addAll(stringList(slot.get("candidates")));
        }
        return formatRuntimeCandidates(candidates.isEmpty() ? List.of("请补充具体值", "取消本次任务") : candidates);
    }

    /** 把模型给出的运行时候选项统一转换为前端可读的 A/B/C 选项。 */
    private List<String> formatRuntimeCandidates(List<String> candidates) {
        List<String> options = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                options.add(optionPrefix(options.size()) + candidate.trim());
            }
        }
        if (options.size() == 1) {
            options.add(optionPrefix(1) + "自定义选择");
        }
        return options;
    }

    /**
     * 生成低负担澄清问题：固定选项优先，业务补充问题作为附加说明。
     */
    private String buildStructuredQuestion(List<String> options, List<String> extraQuestions) {
        String optionText = String.join("；", options);
        String extraText = extraQuestions == null || extraQuestions.isEmpty()
                ? ""
                : " 补充问题：" + String.join("；", extraQuestions);
        return "已收到你的请求，我已识别当前任务目标。为避免执行偏差，请先确认关键缺失信息："
                + optionText
                + "。若你不确定，我将默认采用【A】继续。"
                + extraText;
    }

    /** 为运行时阻塞生成聚焦问题，最终自然语言仍由下一轮模型结合完整上下文输出。 */
    private String buildRuntimeQuestion(String blockingDecision, List<String> options) {
        String decision = blockingDecision == null || blockingDecision.isBlank()
                ? "请选择后续执行口径"
                : blockingDecision;
        return "执行过程中发现一个会影响后续结论的关键分支："
                + decision
                + "。可选项："
                + String.join("；", options)
                + "。";
    }

    /**
     * 根据候选项下标生成用户可读选项前缀。
     */
    private String optionPrefix(int index) {
        return switch (index) {
                case 0 -> "【A】";
                case 1 -> "【B】";
                case 2 -> "【C】";
                default -> "【" + (index + 1) + "】";
        };
    }

    private String success(String toolName, Object data, List<String> rules) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", toolName);
            payload.put("status", "success");
            payload.put("validation_passed", true);
            payload.put("validation_rules", rules);
            payload.put("validation_errors", List.of());
            payload.put("retryable", false);
            payload.put("error_code", "");
            payload.put("data", data);
            payload.put("metadata", Map.of());
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return fail(toolName, ToolErrorCode.TOOL_RESULT_INVALID_JSON,
                    "澄清工具结果序列化失败", false);
        }
    }

    /** 构建澄清工具的统一结构化失败载荷。 */
    private String fail(String toolName, ToolErrorCode code, String error, boolean retryable) {
        try {
            var toolError = DefaultToolErrorClassifier.INSTANCE.classify(code, error, retryable);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", toolName);
            payload.put("status", "failed");
            payload.put("validation_passed", false);
            payload.put("validation_rules", List.of());
            payload.put("validation_errors", List.of(error));
            payload.put("retryable", toolError.retryable());
            payload.put("error_code", toolError.code());
            payload.put("error", ToolResultFactory.publicError(toolError));
            payload.put("data", null);
            payload.put("metadata", Map.of());
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ignored) {
            return "{\"tool\":\"clarify_requirement\",\"status\":\"failed\",\"validation_passed\":false,"
                    + "\"validation_rules\":[],\"validation_errors\":[\"澄清工具返回失败\"],"
                    + "\"retryable\":false,\"error_code\":\""
                    + ToolErrorCode.TOOL_RESULT_INVALID_JSON.code() + "\","
                    + "\"data\":null,\"metadata\":{}}";
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item).trim());
            }
        }
        return values;
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> source)) {
                continue;
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            source.forEach((key, itemValue) -> mapped.put(String.valueOf(key), itemValue));
            values.add(mapped);
        }
        return values;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    /** 会话适配层会拼接历史消息；槽位判断只读取“本轮用户”片段，避免历史示例污染。 */
    private String currentUserTurn(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        int marker = input.lastIndexOf("本轮用户:");
        return marker >= 0 ? input.substring(marker + "本轮用户:".length()).trim() : input.trim();
    }
}
