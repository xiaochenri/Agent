package com.stockmind.bootstrap;

import com.agent.javascope.prompt.AgentBusinessPromptCustomizer;
import com.agent.javascope.tool.runtime.ClarificationBusinessProvider;
import com.agent.javascope.tool.runtime.PlanSafetyValidator;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.stockmind.application.analysis.TechnicalAnalysisService;
import com.stockmind.application.analysis.StockInvestmentAnalysisService;
import com.stockmind.application.dividend.DividendProvider;
import com.stockmind.application.financial.FinancialReportProvider;
import com.stockmind.application.factor.*;
import com.stockmind.application.instrument.InstrumentResolver;
import com.stockmind.application.market.MarketDataProvider;
import com.stockmind.application.research.AnalystResearchProvider;
import com.stockmind.application.risk.SupplementalRiskProvider;
import com.stockmind.application.sector.SectorDataProvider;
import com.stockmind.application.snapshot.PointInTimeStockSnapshotService;
import com.stockmind.bootstrap.business.tool.FinancialReportPeriodResolver;
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
    public InstrumentResolver instrumentResolver() {
        return new InstrumentResolver();
    }

    @Bean
    public PointInTimeStockSnapshotService pointInTimeStockSnapshotService(
            InstrumentResolver instrumentResolver,
            MarketDataProvider marketDataProvider,
            FinancialReportProvider financialReportProvider,
            AnalystResearchProvider analystResearchProvider,
            DividendProvider dividendProvider,
            SectorDataProvider sectorDataProvider,
            SupplementalRiskProvider supplementalRiskProvider) {
        return new PointInTimeStockSnapshotService(instrumentResolver, marketDataProvider,
                financialReportProvider, analystResearchProvider, dividendProvider, sectorDataProvider,
                supplementalRiskProvider);
    }

    @Bean
    public StockFactorProfileService stockFactorProfileService(PointInTimeStockSnapshotService snapshots) {
        return new StockFactorProfileService(snapshots, List.of(
                new ValuationFactorCalculator(), new QualityFactorCalculator(), new GrowthFactorCalculator(),
                new ExpectationRevisionFactorCalculator(), new MomentumRiskFactorCalculator(),
                new ShareholderReturnFactorCalculator()));
    }

    /** 股票分析中间层不访问外部数据，只解释已经完成口径校验的因子画像。 */
    @Bean
    public StockInvestmentAnalysisService stockInvestmentAnalysisService() {
        return new StockInvestmentAnalysisService();
    }

    @Bean
    public ScenarioValuationService scenarioValuationService(PointInTimeStockSnapshotService snapshots) {
        return new ScenarioValuationService(snapshots);
    }

    @Bean
    public AgentBusinessPromptCustomizer stockPromptCustomizer() {
        return new AgentBusinessPromptCustomizer() {
            @Override
            public String customizeDirectReplyPrompt(String basePrompt) {
                return basePrompt + """

                        股票场景补充：
                        - 解释股票业务概念或能力范围时直接回答；涉及实时事实和个股价值判断时进入任务流程获取证据。
                        """;
            }

            @Override
            public String customizeActionPrompt(String basePrompt) {
                return basePrompt + """

                        股票业务指引：
                        - 查询单项事实时选择对应专用工具；分析投资价值时优先读取 stock_factor_profile 返回的 investment_analysis。
                        - 工具已经把指标转换为 analysis_signals、investment_theses 和 analysis_agenda。以 investment_stance、业务信号、投资议题和改变条件为分析基础，不从原始指标重新推导另一套口径。
                        - 不把“具有投资价值/不具有投资价值”作为仅有的两个候选假设。优先把 analysis_agenda 中高权重、高结论敏感度且证据覆盖不足的议题转成待验证假设。
                        - evidence_needs 描述所需证据，candidate_tools 只是能力候选，不是固定调用链。按预期信息增量选择下一项；一个工具可以同时更新多个议题，无需遍历全部候选工具。
                        - 补充工具返回后，说明它更新了哪个 agenda_id、证据覆盖如何变化以及是否改变论点。无方向或仅发现级材料可以关闭一次调查动作，但不能伪装成已解决论点。
                        - unresolved_issues.resolution_status=NOT_AVAILABLE 时使用已有证据形成条件性结论；DISCOVERY_ONLY 表示补充工具只更新材料发现状态。补充工具返回空 business_signals 时不更新投资论点。
                        - analysis_readiness 是基线画像后的初始充分性判断。只有高敏感度议题已有足够证据，或剩余候选能力不可用/不会改变结论时才停止；不能仅因某个字段 NOT_AVAILABLE 就结束全部分析。
                        - 综合结论需要同时说明支持因素、反对因素、未知因素以及改变结论的条件。机构预测、新闻和公告按工具返回的证据类型参与相应论点。
                        - 投资价值回答优先在 final_answer 增加 investment_stance；第一条核心结论明确写出同一立场也可。新增方向性业务信号实质改变立场时，用 stance_revision_signal_ids 说明依据。
                        - 每条核心结论通过 conclusion_evidence 直接列出支持它的 fact、source_step、source_type、as_of 和 basis，让用户能够看到结论依赖的论据。
                        """;
            }

            @Override
            public String customizePlanPrompt(String basePrompt) {
                return basePrompt + """
                        
                        股票业务计划指引：
                        - 按“问题 → 必要事实 → 业务信号 → 投资论点 → 结论”组织步骤。
                        - 综合投资价值使用 stock_factor_profile 的 investment_analysis；围绕 analysis_agenda 的权重、证据覆盖和结论敏感度安排补充步骤。
                        - 计划选择证据能力，不预设必须遍历某组工具；补充步骤说明将更新的 agenda_id 和预期区分作用。
                        - 单项查询和指定计算直接使用对应工具，不扩展成完整投资调查。
                        """;
            }

            @Override
            public String customizeValidationPrompt(String basePrompt) {
                return basePrompt + """
                        
                        股票业务事实校验：
                        - 数字、日期、口径和来源应能追溯到成功工具结果。
                        - 投资立场应与业务中间层的 stance 一致；结论应保留相关业务信号的方向、解释边界和未知项。
                        - 以 conclusion_evidence 中的可读事实和来源判断证据是否充分。
                        - 新增材料只有在能够更新投资论点时才进入结论；事实、预测、观点和未知状态保持工具定义的证据类型。
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
                    questions.add("你想分析哪些股票？请提供 1-3 个六位A股代码（如 600519、000858）。");
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
                boolean hasConsensus = plan.stream()
                        .anyMatch(step -> "analyst_consensus_forecast".equals(step.getTool()));
                if (hasConsensus) return;
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
