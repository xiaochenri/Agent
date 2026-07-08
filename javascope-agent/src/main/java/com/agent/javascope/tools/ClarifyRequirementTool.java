package com.agent.javascope.tools;

import com.agent.javascope.spi.AgentTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ClarifyRequirementTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ClarifyRequirementTool(ClarificationBusinessProvider businessProvider) {
    }

    @AgentTool(
            name = "clarify_requirement",
            description = "通用需求澄清工具。"
                    + " 仅用于任务型请求（analysis/recommendation/query_with_constraints/execution_request）且关键信息缺失时。"
                    + " 非适用场景：身份问答、能力介绍、闲聊问候。"
                    + " 输出结构化决策：action/reasoning/clarification_question/default_assumption/suggested_options。")
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

        Map<String, Object> recognizedFields = new LinkedHashMap<>();
        recognizedFields.put("analysis_object", hasTarget);
        recognizedFields.put("time_window", hasTimeWindow);
        recognizedFields.put("analysis_dimension", hasDimension);

        String action;
        String reasoning;
        String clarificationQuestion = "";
        String defaultAssumption = "";
        List<String> suggestedOptions;

        if (!hasTarget || (isIrreversibleAction && !hasTarget)) {
            action = "ask";
            reasoning = "P0（致命缺失）：缺少关键操作对象，继续执行存在错误风险，需一次性澄清关键槽位。";
            suggestedOptions = buildAskOptions(hasTarget, hasTimeWindow);
            clarificationQuestion = buildStructuredQuestion(normalized, suggestedOptions);
        } else if (!hasTimeWindow && isAnalysisIntent(normalized)) {
            action = "execute_with_guess";
            String assumedWindow = guessByMemory(longTermMemory, "近30天");
            defaultAssumption = "默认时间范围使用" + assumedWindow + "，如需调整可指定近7天/近30天/自定义区间。";
            reasoning = "P1（模糊歧义）：时间范围未给出，先按长期偏好或通用默认值执行，并附带轻量确认。";
            suggestedOptions = List.of(assumedWindow + "（推荐）", "近7天", "自定义区间");
        } else {
            action = "direct_execute";
            String preferredDimension = guessByMemory(longTermMemory, "价格波动+新闻事件+风险提示");
            defaultAssumption = "分析维度默认使用：" + preferredDimension + "。";
            reasoning = "P2（风格/偏好缺失）：关键路径信息完整，非关键缺失采用默认偏好自动填充。";
            suggestedOptions = List.of("保持默认维度（推荐）", "仅看价格波动", "仅看新闻事件");
        }

        Map<String, Object> data = Map.of(
                "user_input", normalized,
                "missing_fields", missingFields,
                "recognized_fields", recognizedFields,
                "memory_snapshot", Map.of(
                        "working_memory", workingMemory,
                        "long_term_memory", longTermMemory),
                "action", action,
                "reasoning", reasoning,
                "clarification_question", clarificationQuestion,
                "default_assumption", defaultAssumption,
                "suggested_options", suggestedOptions);
        return success(
                "clarify_requirement",
                data,
                List.of(
                        "action 必须是 ask/execute_with_guess/direct_execute",
                        "action=ask 时 clarification_question 非空",
                        "suggested_options 至少包含2项"));
    }

    private List<String> buildAskOptions(boolean hasTarget, boolean hasTimeWindow) {
        List<String> options = new ArrayList<>();
        if (!hasTarget) {
            options.add("【A】000957（推荐）");
            options.add("【B】AAPL");
            options.add("【C】自定义标的");
        }
        if (!hasTimeWindow) {
            options.add("【A】近30天（推荐）");
            options.add("【B】近7天");
            options.add("【C】自定义区间");
        }
        if (options.isEmpty()) {
            options.add("【A】保持默认设置（推荐）");
            options.add("【B】自定义参数");
        }
        return options;
    }

    private String buildStructuredQuestion(String userInput, List<String> options) {
        String optionText = String.join("；", options);
        return "已收到你的请求，我已识别当前任务目标。为避免执行偏差，请先确认关键缺失信息："
                + optionText
                + "。若你不确定，我将默认采用【A】继续。";
    }

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
        if (normalized.contains("风险提示")) {
            return "价格波动+新闻事件+风险提示";
        }
        return fallback;
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
