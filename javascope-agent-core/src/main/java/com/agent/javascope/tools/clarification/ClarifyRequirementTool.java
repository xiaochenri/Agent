package com.agent.javascope.tools.clarification;

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
import java.util.regex.Pattern;

public class ClarifyRequirementTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 业务扩展点，用于提供领域候选项、默认值、补充问题和高风险确认策略。 */
    private final ClarificationBusinessProvider businessProvider;
    /** 通用澄清决策策略，把槽位缺失和风险信号映射为 action。 */
    private final ClarificationDecisionPolicy decisionPolicy = new ClarificationDecisionPolicy();

    public ClarifyRequirementTool(ClarificationBusinessProvider businessProvider) {
        this.businessProvider = businessProvider == null ? new ClarificationBusinessProvider() {
        } : businessProvider;
    }

    @AgentTool(
            name = "clarify_requirement",
            title = "需求澄清",
            description = "通用需求澄清工具。"
                    + " 仅用于任务型请求（analysis/recommendation/query_with_constraints/execution_request）且关键信息缺失时。"
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
                        "user_input": {"type": "string", "description": "用户原始输入，可省略，省略时 runtime 使用本轮原始输入。"},
                        "working_memory": {"type": "string", "description": "本轮可用于澄清的短期记忆。"},
                        "long_term_memory": {"type": "string", "description": "用户长期记忆或画像。"},
                        "user_profile": {"type": "string", "description": "用户画像，long_term_memory 的兼容字段。"}
                      },
                      "required": []
                    }
                    """)
    public String clarifyRequirement(Map<String, Object> input, String rawInput) {
        String userInput = firstNonBlank(stringValue(input.get("user_input")), rawInput);
        String normalized = userInput == null ? "" : userInput.trim();
        String workingMemory = stringValue(input.get("working_memory"));
        String longTermMemory = firstNonBlank(stringValue(input.get("long_term_memory")), stringValue(input.get("user_profile")));

        boolean hasTarget = containsTarget(normalized);
        boolean hasTimeWindow = containsTimeWindow(normalized);
        boolean hasDimension = containsAnalysisDimension(normalized);
        boolean isIrreversibleAction = containsIrreversibleAction(normalized);

        List<String> missingFields = new ArrayList<>();
        if (!hasTarget) {
            missingFields.add("分析对象");
        }
        if (!hasTimeWindow) {
            missingFields.add("时间范围");
        }
        if (!hasDimension) {
            missingFields.add("分析维度");
        }
        boolean needsConfirmation = isIrreversibleAction
                || businessProvider.confirmBeforeAction(normalized, missingFields);

        Map<String, Object> recognizedFields = new LinkedHashMap<>();
        recognizedFields.put("analysis_object", hasTarget);
        recognizedFields.put("time_window", hasTimeWindow);
        recognizedFields.put("analysis_dimension", hasDimension);

        List<Map<String, Object>> slots = buildSlots(normalized, longTermMemory, hasTarget, hasTimeWindow, hasDimension);
        List<String> capabilities = businessProvider.capabilities(normalized);
        List<String> businessSuggestedReplies = businessProvider.suggestedReplies(normalized);
        List<String> extraQuestions = businessProvider.extraQuestions(normalized, missingFields);
        String nextStepHint = businessProvider.nextStepHint(normalized, missingFields);

        ClarificationDecision decisionResult = decisionPolicy.decide(
                hasTarget,
                hasTimeWindow,
                isIrreversibleAction,
                needsConfirmation,
                isAnalysisIntent(normalized));
        String action = decisionResult.action();
        String reasoning = decisionResult.reasoning();
        String clarificationQuestion = "";
        String defaultAssumption = "";
        List<String> suggestedOptions;

        if ("ask".equals(action)) {
            suggestedOptions = buildAskOptions(normalized, hasTarget, hasTimeWindow);
            clarificationQuestion = buildStructuredQuestion(suggestedOptions, extraQuestions);
        } else if ("confirm_before_action".equals(action)) {
            suggestedOptions = List.of("【A】确认执行", "【B】取消", "【C】修改对象或范围");
            clarificationQuestion = buildStructuredQuestion(suggestedOptions, extraQuestions);
        } else if ("execute_with_guess".equals(action)) {
            String assumedWindow = defaultValue(normalized, "time_window", longTermMemory, "近期");
            defaultAssumption = "默认时间范围使用" + assumedWindow + "，如需调整可指定其他时间范围。";
            suggestedOptions = recommendedOptions(normalized, "time_window", assumedWindow, "自定义区间");
        } else {
            String preferredDimension = defaultValue(normalized, "analysis_dimension", longTermMemory, "综合分析");
            defaultAssumption = "分析维度默认使用：" + preferredDimension + "。";
            suggestedOptions = recommendedOptions(normalized, "analysis_dimension", preferredDimension, "自定义维度");
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
                        "slots 必须描述关键槽位状态",
                        "suggested_options 至少包含2项"));
    }

    /**
     * 为缺失的 P0/P1 槽位组装用户可选项，候选项来自业务 provider。
     */
    private List<String> buildAskOptions(String userInput, boolean hasTarget, boolean hasTimeWindow) {
        List<String> options = new ArrayList<>();
        if (!hasTarget) {
            appendCandidateOptions(options, "analysis_object", businessProvider.slotCandidates(userInput, "analysis_object"));
        }
        if (!hasTimeWindow) {
            appendCandidateOptions(options, "time_window", businessProvider.slotCandidates(userInput, "time_window"));
        }
        if (options.isEmpty()) {
            options.add("【A】保持默认设置（推荐）");
            options.add("【B】自定义参数");
        }
        if (options.size() == 1) {
            options.add("【B】自定义补充");
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

    /**
     * 粗判是否为分析类任务，用于决定缺时间范围时是否可默认执行。
     */
    private boolean isAnalysisIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("分析")
                || normalized.contains("报告")
                || normalized.contains("风险")
                || normalized.contains("是否值得")
                || normalized.contains("关注");
    }

    /**
     * 识别通用不可逆或外部副作用动作，命中后需要确认或补齐对象。
     */
    private boolean containsIrreversibleAction(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("删除")
                || normalized.contains("清空")
                || normalized.contains("发送")
                || normalized.contains("转账")
                || normalized.contains("关闭")
                || normalized.contains("drop");
    }

    /**
     * 默认值优先来自业务 provider，其次从记忆里猜测，最后使用通用兜底值。
     */
    private String defaultValue(String userInput, String slotName, String memory, String fallback) {
        String businessDefault = businessProvider.defaultValue(userInput, slotName, memory);
        if (businessDefault != null && !businessDefault.isBlank()) {
            return businessDefault.trim();
        }
        return guessByMemory(memory, fallback);
    }

    /**
     * 通用记忆兜底推断，只保留领域无关的时间范围偏好。
     */
    private String guessByMemory(String memory, String fallback) {
        if (memory == null || memory.isBlank()) {
            return fallback;
        }
        String normalized = memory.toLowerCase();
        if (normalized.contains("近7天")) {
            return "近7天";
        }
        if (normalized.contains("近30天")) {
            return "近30天";
        }
        return fallback;
    }

    /**
     * 构建结构化槽位列表，供前端渲染和后续恢复执行使用。
     */
    private List<Map<String, Object>> buildSlots(
            String userInput, String memory, boolean hasTarget, boolean hasTimeWindow, boolean hasDimension) {
        List<Map<String, Object>> slots = new ArrayList<>();
        slots.add(buildSlot(userInput, memory, "analysis_object", "分析对象", hasTarget, "P0"));
        slots.add(buildSlot(userInput, memory, "time_window", "时间范围", hasTimeWindow, "P1"));
        slots.add(buildSlot(userInput, memory, "analysis_dimension", "分析维度", hasDimension, "P2"));
        return slots;
    }

    /**
     * 构建单个槽位状态，包含是否缺失、默认值和业务候选项。
     */
    private Map<String, Object> buildSlot(
            String userInput, String memory, String name, String label, boolean present, String priority) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("name", name);
        slot.put("label", label);
        slot.put("status", present ? "present" : "missing");
        slot.put("priority", priority);
        slot.put("value", present ? "已识别于用户输入" : null);
        slot.put("default_value", defaultValue(userInput, name, memory, ""));
        slot.put("candidates", businessProvider.slotCandidates(userInput, name));
        return slot;
    }

    /**
     * 将业务候选项格式化为【A】/【B】形式，便于用户短回复。
     */
    private List<String> formatCandidates(String slotName, List<String> candidates) {
        List<String> options = new ArrayList<>();
        appendCandidateOptions(options, slotName, candidates);
        return options;
    }

    /**
     * 追加候选项并保持编号全局递增，避免多个缺失槽位都出现【A】。
     */
    private void appendCandidateOptions(List<String> options, String slotName, List<String> candidates) {
        List<String> values = candidates == null ? List.of() : candidates;
        for (int i = 0; i < values.size(); i++) {
            String prefix = optionPrefix(options.size());
            options.add(prefix + values.get(i));
        }
        if (values.isEmpty()) {
            options.add(optionPrefix(options.size()) + "自定义" + slotLabel(slotName));
        }
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

    /**
     * 为 execute_with_guess/direct_execute 生成“默认推荐 + 可替换项”的提示选项。
     */
    private List<String> recommendedOptions(String userInput, String slotName, String recommended, String fallback) {
        List<String> candidates = new ArrayList<>(businessProvider.slotCandidates(userInput, slotName));
        if (recommended != null && !recommended.isBlank() && !candidates.contains(recommended)) {
            candidates.add(0, recommended);
        }
        if (!candidates.contains(fallback)) {
            candidates.add(fallback);
        }
        return formatCandidates(slotName, candidates);
    }

    /**
     * 槽位英文名到中文展示名的兜底映射。
     */
    private String slotLabel(String slotName) {
        return switch (slotName) {
            case "analysis_object" -> "分析对象";
            case "time_window" -> "时间范围";
            case "analysis_dimension" -> "分析维度";
            default -> "参数";
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
            payload.put("data", data);
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return fail(toolName, "json 序列化失败", false);
        }
    }

    private String fail(String toolName, String error, boolean retryable) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", toolName);
            payload.put("status", "failed");
            payload.put("validation_passed", false);
            payload.put("validation_rules", List.of());
            payload.put("validation_errors", List.of(error));
            payload.put("retryable", retryable);
            payload.put("data", null);
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ignored) {
            return "{\"tool\":\"clarify_requirement\",\"status\":\"failed\",\"retryable\":false}";
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private boolean containsTarget(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (Pattern.compile("\\b\\d{6}\\b").matcher(text).find()) {
            return true;
        }
        if (Pattern.compile("\\b[A-Z]{1,5}\\b").matcher(text.toUpperCase()).find()) {
            return true;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("对象")
                || normalized.contains("主题")
                || normalized.contains("标的")
                || normalized.contains("内容")
                || normalized.contains("项目");
    }

    private boolean containsTimeWindow(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("今天")
                || normalized.contains("近期")
                || normalized.contains("最近")
                || normalized.contains("近一周")
                || normalized.contains("近一月")
                || normalized.contains("本周")
                || normalized.contains("本月")
                || normalized.contains("today")
                || normalized.contains("week")
                || normalized.contains("month");
    }

    private boolean containsAnalysisDimension(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("维度")
                || normalized.contains("重点")
                || normalized.contains("角度")
                || normalized.contains("指标")
                || normalized.contains("关注点")
                || normalized.contains("输出")
                || normalized.contains("值得关注")
                || normalized.contains("是否关注")
                || normalized.contains("值不值得")
                || normalized.contains("能买吗")
                || normalized.contains("可不可以买")
                || normalized.contains("可不可以")
                || normalized.contains("风险大吗")
                || normalized.contains("要不要")
                || normalized.contains("是否买入")
                || normalized.contains("是否持有");
    }
}
