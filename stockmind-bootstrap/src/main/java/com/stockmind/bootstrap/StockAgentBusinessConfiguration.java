package com.stockmind.bootstrap;

import com.agent.javascope.prompt.AgentBusinessPromptCustomizer;
import com.agent.javascope.tools.ClarificationBusinessProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StockAgentBusinessConfiguration {

    @Bean
    public AgentBusinessPromptCustomizer stockPromptCustomizer() {
        return new AgentBusinessPromptCustomizer() {
            @Override
            public String customizeDirectReplyPrompt(String basePrompt) {
                return basePrompt + """

                        直答业务规则补充（股票场景）：
                        - 你是股票分析助手，聚焦个股/板块的事实分析、证据整理与风险提示
                        - 对非任务型请求（如“你是谁”“你能帮我做什么”），必须直接输出面向用户的自然回答，不得输出“非任务型请求/无需进入工具链”等内部流程描述
                        - 当用户问“你能帮我做什么”时，core_conclusions 至少包含：你能提供的能力列表 + 1-2条用户可直接输入的示例
                        - 当用户问“你是谁”时，core_conclusions 应先说明你的股票分析助手角色，再给出可执行帮助范围
                        - 直答场景禁止承诺实时或确定性投资结论；涉及投资判断时必须提示需要结合行情、新闻、财报等证据进一步分析
                        """;
            }

            @Override
            public String customizeActionPrompt(String basePrompt) {
                return basePrompt + """

                        角色设定（股票分析助手）：
                        - 你是一个用于股票分析的助手，聚焦个股/板块的事实分析与风险提示

                        业务规则补充（股票场景）：
                        - 工具使用场景必须匹配步骤目标，不允许“为调用而调用”
                        - 股票任务中，进入业务检索前需保证“标的+时间范围”明确；分析维度缺失时可按默认风险框架执行（价格波动/事件催化/基本面信号）
                        - 对非任务型请求（如“你是谁”“你能帮我做什么”），必须直接输出面向用户的自然回答，不得输出“非任务型请求/无需进入工具链”等内部流程描述
                        - 当用户问“你能帮我做什么”时，core_conclusions 至少包含：你能提供的能力列表 + 1-2条用户可直接输入的示例
                        - 当用户问“你是谁”时，core_conclusions 应先说明你的角色，再给出可执行帮助范围
                        - 当任务可单步完成时按需直接调用工具：价格类走行情工具；事件类走新闻工具；财报类走知识库工具
                        - final_answer 的关键结论必须能在执行日志中找到依据；若证据不足，继续调用工具而不是直接下结论
                        - 回答应优先覆盖：行情表现、事件催化、基本面信号、风险点
                        - 涉及“今天/近期”必须给出明确日期，避免模糊时效描述
                        - 不得把不确定信息表述为确定结论
                        """;
            }

            @Override
            public String customizePlanPrompt(String basePrompt) {
                return basePrompt + """
                        
                        业务规则补充（股票场景）：
                        - 计划必须围绕“标的、时间、证据来源”组织，确保结论可追溯
                        - 若已知缺少标的或时间窗，不要直接做行情/新闻/知识检索；仅缺分析维度时可按默认框架继续
                        - 若用户诉求是“推荐哪些股票”，建议先补齐风险偏好或筛选标准
                        - 禁止输出空入参调用：每个业务工具都必须包含完成当前步骤所需的最小参数
                        - 若涉及“今天/近期”，步骤里必须包含时效性核验
                        - 遇到参数映射失败时，优先新增“参数澄清或归一化”步骤，再继续行情/新闻/财报检索
                        """;
            }

            @Override
            public String customizeValidationPrompt(String basePrompt) {
                return basePrompt + """
                        
                        业务校验补充（股票场景）：
                        - 结论必须可在 execution_log 找到证据
                        - 结论跳步或缺证据时 passed=false
                        """;
            }
        };
    }

    @Bean
    public ClarificationBusinessProvider stockClarificationBusinessProvider() {
        return new ClarificationBusinessProvider() {
            @Override
            public List<String> capabilities(String userInput) {
                return List.of(
                        "行情分析：根据 symbol 给出价格、涨跌幅、成交量等信息",
                        "新闻分析：按关键词检索近期事件与潜在催化",
                        "财报分析：检索财报/年报/季报关键证据",
                        "综合结论：输出核心结论、证据、风险点和下一步建议");
            }

            @Override
            public List<String> suggestedReplies(String userInput) {
                String normalized = userInput == null ? "" : userInput.trim();
                boolean hasTarget = containsTarget(normalized);
                boolean hasTime = containsTimeWindow(normalized);
                boolean hasFocus = containsFocus(normalized);

                String target = inferTarget(normalized);
                String time = inferTimeWindow(normalized);
                String focus = inferFocus(normalized);

                List<String> replies = new ArrayList<>();
                List<String> parts = new ArrayList<>();
                parts.add(hasTarget ? target : "AAPL");
                if (!hasTime) {
                    parts.add(time);
                }
                if (!hasFocus) {
                    parts.add(focus);
                }
                replies.add("最短回复：" + String.join(" + ", parts));
                if (!hasTarget) {
                    replies.add("示例：600519 + 近一周 + 价格波动/新闻事件");
                }
                if (!hasFocus && normalized.contains("风险")) {
                    replies.add("可选风险维度：价格波动、新闻事件、财报基本面");
                }
                return replies;
            }

            @Override
            public List<String> extraQuestions(String userInput, List<String> missingFields) {
                List<String> questions = new java.util.ArrayList<>();
                if (missingFields.contains("分析对象")) {
                    questions.add("你想分析哪些股票？请提供 1-3 个代码（如 AAPL、TSLA、600519）。");
                }
                if (missingFields.contains("时间范围")) {
                    questions.add("你关注的时间范围是今天、近一周，还是近一月？");
                }
                if (missingFields.contains("分析维度")) {
                    questions.add("你希望重点看哪类信息：行情、新闻催化、财报基本面、还是风险提示？");
                }
                if (userInput != null && !userInput.isBlank()) {
                    String normalized = userInput.toLowerCase();
                    if (normalized.contains("推荐") || normalized.contains("哪些股票")) {
                        questions.add("你的风险偏好是保守、平衡还是激进？");
                    }
                }
                return questions;
            }

            @Override
            public String nextStepHint(String userInput, List<String> missingFields) {
                return "请先补充上述关键项；收到后我会继续调用行情/新闻/财报工具完成分析。";
            }

            @Override
            public List<String> slotCandidates(String userInput, String slotName) {
                return switch (slotName) {
                    case "analysis_object" -> List.of("AAPL（推荐）", "600519", "自定义股票代码");
                    case "time_window" -> List.of("近一周（推荐）", "近一月", "自定义区间");
                    case "analysis_dimension" -> List.of("价格波动/新闻事件（推荐）", "财报基本面/风险提示", "自定义维度");
                    default -> List.of();
                };
            }

            @Override
            public String defaultValue(String userInput, String slotName, String memory) {
                return switch (slotName) {
                    case "analysis_object" -> inferTarget(userInput);
                    case "time_window" -> inferTimeWindow(firstNonBlank(memory, userInput));
                    case "analysis_dimension" -> inferFocus(firstNonBlank(memory, userInput));
                    default -> "";
                };
            }

            @Override
            public boolean confirmBeforeAction(String userInput, List<String> missingFields) {
                if (userInput == null || userInput.isBlank()) {
                    return false;
                }
                String normalized = userInput.toLowerCase();
                return normalized.contains("买入")
                        || normalized.contains("卖出")
                        || normalized.contains("下单")
                        || normalized.contains("清仓")
                        || normalized.contains("转账");
            }

            private boolean containsTarget(String text) {
                if (text == null || text.isBlank()) {
                    return false;
                }
                if (Pattern.compile("\\b\\d{6}\\b").matcher(text).find()) {
                    return true;
                }
                return Pattern.compile("\\b[A-Z]{1,5}\\b").matcher(text.toUpperCase()).find();
            }

            private boolean containsTimeWindow(String text) {
                if (text == null || text.isBlank()) {
                    return false;
                }
                String normalized = text.toLowerCase();
                return normalized.contains("今天")
                        || normalized.contains("近一周")
                        || normalized.contains("近一月")
                        || normalized.contains("近期")
                        || normalized.contains("最近")
                        || normalized.contains("本周")
                        || normalized.contains("本月")
                        || normalized.contains("today")
                        || normalized.contains("week")
                        || normalized.contains("month");
            }

            private boolean containsFocus(String text) {
                if (text == null || text.isBlank()) {
                    return false;
                }
                String normalized = text.toLowerCase();
                return normalized.contains("行情")
                        || normalized.contains("新闻")
                        || normalized.contains("催化")
                        || normalized.contains("财报")
                        || normalized.contains("基本面")
                        || normalized.contains("风险")
                        || normalized.contains("值得关注")
                        || normalized.contains("是否关注")
                        || normalized.contains("值不值得")
                        || normalized.contains("能买吗")
                        || normalized.contains("要不要")
                        || normalized.contains("维度")
                        || normalized.contains("重点");
            }

            private String inferTarget(String text) {
                if (text == null || text.isBlank()) {
                    return "AAPL";
                }
                Matcher cnMatcher = Pattern.compile("\\b\\d{6}\\b").matcher(text);
                if (cnMatcher.find()) {
                    return cnMatcher.group();
                }
                Matcher usMatcher = Pattern.compile("\\b[A-Z]{1,5}\\b").matcher(text.toUpperCase());
                if (usMatcher.find()) {
                    return usMatcher.group();
                }
                return "AAPL";
            }

            private String inferTimeWindow(String text) {
                if (text == null || text.isBlank()) {
                    return "近一周";
                }
                String normalized = text.toLowerCase();
                if (normalized.contains("今天") || normalized.contains("today")) {
                    return "今天";
                }
                if (normalized.contains("近一月") || normalized.contains("本月") || normalized.contains("month")) {
                    return "近一月";
                }
                return "近一周";
            }

            private String inferFocus(String text) {
                if (text == null || text.isBlank()) {
                    return "价格波动/新闻事件";
                }
                String normalized = text.toLowerCase();
                if (normalized.contains("财报") || normalized.contains("基本面")) {
                    return "财报基本面/风险提示";
                }
                if (normalized.contains("新闻") || normalized.contains("催化") || normalized.contains("事件")) {
                    return "新闻事件/风险提示";
                }
                if (normalized.contains("风险")) {
                    return "价格波动/新闻事件/财报基本面";
                }
                return "价格波动/新闻事件";
            }

            private String firstNonBlank(String first, String second) {
                if (first != null && !first.isBlank()) {
                    return first;
                }
                return second == null ? "" : second;
            }
        };
    }
}
