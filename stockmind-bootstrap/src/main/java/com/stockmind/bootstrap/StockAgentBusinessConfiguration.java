package com.stockmind.bootstrap;

import com.agent.javascope.prompt.AgentBusinessPromptCustomizer;
import com.agent.javascope.tool.runtime.ClarificationBusinessProvider;
import com.agent.javascope.tool.runtime.PlanSafetyValidator;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.stockmind.application.analysis.TechnicalAnalysisService;
import com.stockmind.application.market.MarketDataProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StockAgentBusinessConfiguration {

    @Bean
    public TechnicalAnalysisService technicalAnalysisService(MarketDataProvider marketDataProvider) {
        return new TechnicalAnalysisService(marketDataProvider);
    }

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
                        - 股票任务中，标的必须明确；用户未给时间范围时不填写具体历史日期，由股票工具统一解析为截至当前日期的近一个月窗口
                        - 财报期间与行情时间窗是不同口径；自然语言已明确财报期间时由下游转换为 report_period/report_document，不得因参数格式澄清
                        - 只有用户未表达任何可确定的年份、报告期或文档时，才把“要分析哪一期财报”作为缺失业务语义
                        - 当任务可单步完成时按需直接调用工具：价格类走行情工具；事件类走新闻工具；财报类走知识库工具
                        - EPS/PE 任务必须使用 financial_report_metrics 获取结构化财报字段，再使用 financial_metric_calculator 计算；stock_snapshot_analysis 不负责计算财务指标
                        - 新闻或知识库不可用时，基于已获得的行情与技术证据完成结论，不要重复调用同一工具
                        - 技术指标优先调用 technical_indicator_snapshot；不要先输出完整 historical_bars 再逐个重复调用指标，除非用户明确要求查看K线或单项指标
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
                        - 财报任务必须使用 report_period/report_document 组织，不得把行情 time_window 当作财报期间；缺少财报期间时不应进入规划器
                        - “缺少财报期间”按业务语义判断，不按参数字符串判断；例如“2024年第一季度”已经完整，不得因工具需要 2024Q1 或 2024-03-31 而澄清
                        - EPS/PE 计划必须调用 financial_report_metrics 和 financial_metric_calculator，不得用 knowledge_search 或 stock_snapshot_analysis 冒充结构化取数和计算
                        - 若缺少标的，不要直接做行情/新闻/知识检索；仅缺时间窗时不要自行填写具体日期，由股票工具统一解析截至当前日期近一个月窗口
                        - 若用户诉求是“推荐哪些股票”，建议先补齐风险偏好或筛选标准
                        - 禁止输出空入参调用：每个业务工具都必须包含完成当前步骤所需的最小参数
                        - 若涉及“今天/近期”，步骤里必须包含时效性核验
                        - 新闻和知识库属于补充证据，不得作为技术总结的硬依赖；证据不足时基于已获得的数据完成总结并说明待补充维度
                        - 最终总结必须传入 technical_indicator_snapshot 的结构化 data 作为 technical_data，不能用“技术指标结果”等静态占位文本代替
                        - stock_snapshot_analysis 返回 analysis_readiness 后，必须先阅读 recommended_next_action：当 ready_for_answer=true 且 recommended_next_action=FINAL_ANSWER 时，应直接输出 final_answer；只有用户问题存在 required_gaps 或能明确说明新增证据价值时，才继续调用 suggested_tools
                        - 若历史记忆包含 type=business_decision，优先按其中的 completed_scopes 复用已有股票证据；当 recommended_action=FINAL_ANSWER 且 repeat_policy=REUSE_EXISTING_RESULT 时，相同标的、时间窗、复权口径的问题不得重复调用行情、K线或技术指标工具
                        - stock_snapshot_analysis 的 answer_context 是最终回答的首要业务材料：直接面向用户说明行情、技术判断、综合价值判断和风险，不得把 decision、analysis_readiness、覆盖范围或计划过程写入 final_answer
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
                parts.add(hasTarget ? target : "股票代码");
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
                if (missingFields.contains("财报期间") || missingFields.contains("指定财报")) {
                    questions.add("请指定财报年份和类型，例如2024年报、2025一季报，或提供具体财报文件。");
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
                    case "analysis_object" -> List.of("输入股票代码（推荐）", "按股票名称识别", "取消本次分析");
                    case "time_window" -> List.of("近一周（推荐）", "近一月", "自定义区间");
                    case "analysis_dimension" -> List.of("价格波动/新闻事件（推荐）", "财报基本面/风险提示", "自定义维度");
                    case "report_period" -> List.of("最新已披露年报", "最新已披露季报", "自定义年份和报告期");
                    case "report_document" -> List.of("提供财报文件", "按股票代码和报告期检索", "取消本次计算");
                    default -> List.of();
                };
            }

            @Override
            public String defaultValue(String userInput, String slotName, String memory) {
                return switch (slotName) {
                    // P0 对象没有安全默认值，缺失时必须由模型发起澄清，不能回退到示例股票。
                    case "analysis_object" -> containsTarget(userInput) ? inferTarget(userInput) : "";
                    case "time_window" -> inferTimeWindow(firstNonBlank(memory, userInput));
                    case "analysis_dimension" -> inferFocus(firstNonBlank(memory, userInput));
                    // 财报期间影响财务指标口径，业务层不提供静默默认值。
                    case "report_period", "report_document" -> "";
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
                Matcher matcher = Pattern.compile("\\b[A-Z]{1,5}\\b").matcher(text.toUpperCase());
                while (matcher.find()) {
                    String candidate = matcher.group();
                    if (!List.of("EPS", "PE", "ROE", "ROA", "PB", "ETF", "IPO").contains(candidate)) {
                        return true;
                    }
                }
                return false;
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
                    return "";
                }
                Matcher cnMatcher = Pattern.compile("\\b\\d{6}\\b").matcher(text);
                if (cnMatcher.find()) {
                    return cnMatcher.group();
                }
                Matcher usMatcher = Pattern.compile("\\b[A-Z]{1,5}\\b").matcher(text.toUpperCase());
                while (usMatcher.find()) {
                    String candidate = usMatcher.group();
                    if (!List.of("EPS", "PE", "ROE", "ROA", "PB", "ETF", "IPO").contains(candidate)) {
                        return candidate;
                    }
                }
                return "";
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

    /**
     * 股票计划安全校验：symbol/ticker 等关键执行对象必须能追溯到用户输入或前序步骤引用。
     * 这条规则专门阻止规划模型在用户未指定标的时擅自使用 AAPL、600519 等示例值。
     */
    @Bean
    public PlanSafetyValidator stockPlanSafetyValidator() {
        return new PlanSafetyValidator() {
            @Override
            public List<String> validate(String userInput, List<PlanStepDefinition> plan) {
                List<String> errors = new ArrayList<>();
                String source = currentUserTurn(userInput).toUpperCase();
                for (int i = 0; i < plan.size(); i++) {
                    validateCriticalValues(plan.get(i).getInput(), source, "plan[" + i + "].input", errors);
                }
                validateFinancialCalculationPlan(source, plan, errors);
                return errors;
            }

            /** EPS+PE 属于强契约任务：必须明确财报期间，并使用结构化取数和确定性计算工具。 */
            private void validateFinancialCalculationPlan(
                    String source, List<PlanStepDefinition> plan, List<String> errors) {
                if (!(source.contains("EPS") && source.contains("PE"))) {
                    return;
                }
                if (!hasExplicitReportPeriod(source)) {
                    errors.add("EPS/PE 任务缺少 report_period 或 report_document；必须先调用 clarify_requirement 获取用户确认");
                }
                boolean hasReportTool = plan.stream()
                        .anyMatch(step -> "financial_report_metrics".equals(step.getTool()));
                boolean hasCalculator = plan.stream()
                        .anyMatch(step -> "financial_metric_calculator".equals(step.getTool()));
                if (!hasReportTool) {
                    errors.add("EPS/PE 计划必须包含 financial_report_metrics，knowledge_search 不能替代结构化财报取数");
                }
                if (!hasCalculator) {
                    errors.add("EPS/PE 计划必须包含 financial_metric_calculator，stock_snapshot_analysis 不负责指标计算");
                    return;
                }
                PlanStepDefinition calculator = plan.stream()
                        .filter(step -> "financial_metric_calculator".equals(step.getTool()))
                        .findFirst().orElse(null);
                if (calculator == null) return;
                List<String> outputPaths = calculator.getRequiredOutputs().stream()
                        .map(item -> item.getPath().toLowerCase())
                        .toList();
                if (!outputPaths.contains("data.eps") || !outputPaths.contains("data.pe")) {
                    errors.add("financial_metric_calculator 步骤的 required_outputs 必须同时包含 data.eps 和 data.pe");
                }
            }

            private boolean hasExplicitReportPeriod(String source) {
                String normalized = source == null ? "" : source;
                return FinancialReportPeriodResolver.findInText(normalized).isPresent()
                        || normalized.contains("最新财报")
                        || normalized.contains("最新年报")
                        || normalized.contains("最新季报")
                        || normalized.contains("财报文件")
                        || normalized.contains("REPORT_PERIOD");
            }

            /** 只信任本轮用户明确输入，不能把历史助手举例当作用户确认的股票代码。 */
            private String currentUserTurn(String input) {
                if (input == null || input.isBlank()) {
                    return "";
                }
                int marker = input.lastIndexOf("本轮用户:");
                return marker >= 0 ? input.substring(marker + "本轮用户:".length()).trim() : input.trim();
            }

            @SuppressWarnings("unchecked")
            private void validateCriticalValues(
                    Object value, String source, String path, List<String> errors) {
                if (value instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String key = String.valueOf(entry.getKey());
                        Object child = entry.getValue();
                        String childPath = path + "." + key;
                        if (isSecurityKey(key) && child instanceof String text && isLiteralSecurityCode(text)
                                && !source.contains(text.trim().toUpperCase())) {
                            errors.add(childPath + " 的关键标的 " + text
                                    + " 无法追溯到用户输入；必须先调用 clarify_requirement 获取用户确认");
                        }
                        validateCriticalValues(child, source, childPath, errors);
                    }
                } else if (value instanceof List<?> list) {
                    for (int i = 0; i < list.size(); i++) {
                        validateCriticalValues(list.get(i), source, path + "[" + i + "]", errors);
                    }
                }
            }

            private boolean isSecurityKey(String key) {
                String normalized = key == null ? "" : key.toLowerCase();
                return "symbol".equals(normalized) || "ticker".equals(normalized);
            }

            private boolean isLiteralSecurityCode(String value) {
                if (value == null || value.isBlank() || value.contains("$ref")) {
                    return false;
                }
                String normalized = value.trim().toUpperCase();
                return normalized.matches("[A-Z]{1,5}") || normalized.matches("\\d{6}");
            }
        };
    }
}
