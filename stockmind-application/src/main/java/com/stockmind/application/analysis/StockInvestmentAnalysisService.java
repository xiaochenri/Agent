package com.stockmind.application.analysis;

import com.stockmind.domain.analysis.AnalysisEvidence;
import com.stockmind.domain.analysis.AnalysisAgendaItem;
import com.stockmind.domain.analysis.AnalysisCapability;
import com.stockmind.domain.analysis.AnalysisEvidenceNeed;
import com.stockmind.domain.analysis.AnalysisReadiness;
import com.stockmind.domain.analysis.BusinessSignal;
import com.stockmind.domain.analysis.ConclusionSensitivity;
import com.stockmind.domain.analysis.DecisionImportance;
import com.stockmind.domain.analysis.EvidenceCoverage;
import com.stockmind.domain.analysis.EvidenceResolutionStatus;
import com.stockmind.domain.analysis.InvestmentStance;
import com.stockmind.domain.analysis.InvestmentThesis;
import com.stockmind.domain.analysis.InvestmentThesisType;
import com.stockmind.domain.analysis.SignalDirection;
import com.stockmind.domain.analysis.SignalStrength;
import com.stockmind.domain.analysis.StockInvestmentAnalysis;
import com.stockmind.domain.analysis.ThesisStatus;
import com.stockmind.domain.analysis.UnresolvedIssue;
import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorCategoryResult;
import com.stockmind.domain.factor.FactorScore;
import com.stockmind.domain.factor.FactorValue;
import com.stockmind.domain.factor.StockFactorProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 股票业务分析中间层。
 *
 * <p>该服务不再把原始指标直接交给语言模型猜测含义，而是先完成三件事：
 * 统一比较口径、生成有方向的业务信号、把信号归并到投资论点。语言模型只需要
 * 权衡这些论点并解释结论，不需要重新计算财务指标或自行发明因果关系。</p>
 */
public final class StockInvestmentAnalysisService {
    private static final BigDecimal LOW_PERCENTILE = BigDecimal.valueOf(30);
    private static final BigDecimal HIGH_PERCENTILE = BigDecimal.valueOf(70);
    private static final BigDecimal MATERIAL_GROWTH_CHANGE = BigDecimal.valueOf(15);
    private static final BigDecimal MATERIAL_PROFIT_REVENUE_GAP = BigDecimal.valueOf(30);
    private static final BigDecimal MIN_CASH_CONVERSION = BigDecimal.valueOf(0.8);

    /** 把确定性因子画像转换成模型可以直接使用的投资分析语义。 */
    public StockInvestmentAnalysis analyze(StockFactorProfile profile) {
        List<BusinessSignal> signals = new ArrayList<>();
        addValuationSignals(profile, signals);
        addGrowthSignals(profile, signals);
        addEarningsQualitySignals(profile, signals);
        addExpectationSignals(profile, signals);
        addCategorySignal(profile, signals, FactorCategory.MOMENTUM_RISK,
                InvestmentThesisType.PRICE_RISK, "price_risk", "价格与波动状态");
        addCategorySignal(profile, signals, FactorCategory.SHAREHOLDER_RETURN,
                InvestmentThesisType.SHAREHOLDER_RETURN, "shareholder_return", "股东回报状态");

        List<InvestmentThesis> theses = buildTheses(profile, signals);
        List<UnresolvedIssue> unresolvedIssues = unresolvedIssues(profile, signals);
        List<AnalysisAgendaItem> analysisAgenda = analysisAgenda(theses, signals);
        AnalysisReadiness analysisReadiness = analysisReadiness(profile, analysisAgenda);
        List<String> unresolved = unresolvedIssues.stream().map(UnresolvedIssue::question).toList();
        InvestmentStance stance = stance(profile, theses);
        BigDecimal confidence = confidence(profile, theses);
        return new StockInvestmentAnalysis(
                profile.symbol(), profile.name(), profile.asOf(), stance, confidence,
                "6-12个月", judgment(stance), signals, theses, analysisAgenda, analysisReadiness,
                unresolvedIssues, unresolved, changeConditions(theses));
    }

    /**
     * 把“当前有哪些信号”进一步转换为“还需要回答哪些投资问题”。
     *
     * <p>分析议题只声明证据能力和结论敏感性，不把具体工具顺序写死。这样运行时可以根据
     * 已有观察选择信息增量最高的能力，也可以在某项证据无增量时及时停止。</p>
     */
    private List<AnalysisAgendaItem> analysisAgenda(
            List<InvestmentThesis> theses, List<BusinessSignal> signals) {
        InvestmentThesis valuation = thesis(theses, InvestmentThesisType.VALUATION);
        InvestmentThesis growth = thesis(theses, InvestmentThesisType.GROWTH);
        InvestmentThesis quality = thesis(theses, InvestmentThesisType.EARNINGS_QUALITY);
        InvestmentThesis expectations = thesis(theses, InvestmentThesisType.EXPECTATIONS);
        InvestmentThesis eventRisk = thesis(theses, InvestmentThesisType.EVENT_RISK);

        List<AnalysisAgendaItem> agenda = new ArrayList<>();
        agenda.add(new AnalysisAgendaItem(
                "valuation_safety_margin", InvestmentThesisType.VALUATION,
                "当前价格是否具有足够安全边际", status(valuation), DecisionImportance.HIGH,
                baselineCoverage(valuation),
                hasSignals(signals, "valuation_history", "valuation_peer_context")
                        ? "自身历史估值位置与宽行业样本相对估值给出的含义不完全一致"
                        : "当前估值证据尚不足以同时判断历史位置和相对估值",
                List.of(
                        need("前向PE与未来盈利预测", AnalysisCapability.FORWARD_VALUATION,
                                EvidenceResolutionStatus.AVAILABLE, "判断当前价格对应的预期盈利要求"),
                        need("不同盈利和估值倍数假设下的价值区间", AnalysisCapability.SCENARIO_VALUATION,
                                EvidenceResolutionStatus.AVAILABLE, "观察结论对盈利和估值假设的敏感度"),
                        need("按商业模式和盈利结构筛选的严格可比公司", AnalysisCapability.STRICT_PEER_VALIDATION,
                                EvidenceResolutionStatus.NOT_AVAILABLE, "验证宽行业折价是否构成真实低估")),
                ConclusionSensitivity.HIGH));
        agenda.add(new AnalysisAgendaItem(
                "growth_sustainability", InvestmentThesisType.GROWTH,
                "当前增长水平能否持续", status(growth), DecisionImportance.HIGH,
                baselineCoverage(growth),
                hasSignals(signals, "growth_level", "growth_momentum")
                        ? "当期增长水平与增长动量给出的方向不一致"
                        : "当前增长证据尚不足以同时判断增长水平和持续性",
                List.of(
                        need("多期收入、利润和现金流趋势", AnalysisCapability.FINANCIAL_TREND,
                                EvidenceResolutionStatus.AVAILABLE, "区分单期波动与持续增长变化"),
                        need("未来年度EPS预测及预测分歧", AnalysisCapability.FORWARD_VALUATION,
                                EvidenceResolutionStatus.AVAILABLE, "观察市场对增长持续性的预期"),
                        need("机构对经营驱动因素的公开观点", AnalysisCapability.RESEARCH_CONTEXT,
                                EvidenceResolutionStatus.DISCOVERY_ONLY, "发现可能解释增长变化的业务线索")),
                sensitivity(growth)));
        agenda.add(new AnalysisAgendaItem(
                "earnings_quality_durability", InvestmentThesisType.EARNINGS_QUALITY,
                "利润增长是否具有持续经营和现金流支撑", status(quality), DecisionImportance.HIGH,
                baselineCoverage(quality),
                hasSignals(signals, "earnings_attribution", "cash_conversion")
                        ? "利润增速、收入增速与经营现金流覆盖程度尚未形成一致解释"
                        : "当前证据尚不足以同时判断利润来源和现金流支撑",
                List.of(
                        need("多期经营现金流与净利润转化趋势", AnalysisCapability.FINANCIAL_TREND,
                                EvidenceResolutionStatus.AVAILABLE, "判断现金转化偏弱是单期还是持续现象"),
                        need("扣非净利润或非经常性损益占比", AnalysisCapability.EARNINGS_ATTRIBUTION,
                                EvidenceResolutionStatus.NOT_AVAILABLE, "拆分利润增长来源")),
                sensitivity(quality)));
        agenda.add(new AnalysisAgendaItem(
                "expectation_gap", InvestmentThesisType.EXPECTATIONS,
                "市场一致预期是否已经充分反映当前增长", status(expectations), DecisionImportance.MEDIUM,
                baselineCoverage(expectations),
                "评级分布只能描述观点倾向，仍需结合预测分歧和前向估值判断预期高低",
                List.of(
                        need("一致预期EPS、样本数、分歧区间和前向PE", AnalysisCapability.FORWARD_VALUATION,
                                EvidenceResolutionStatus.AVAILABLE, "判断市场预期及其隐含估值"),
                        need("机构研报中的经营假设", AnalysisCapability.RESEARCH_CONTEXT,
                                EvidenceResolutionStatus.DISCOVERY_ONLY, "补充预期背后的观点依据")),
                ConclusionSensitivity.MEDIUM));
        agenda.add(new AnalysisAgendaItem(
                "recent_event_impact", InvestmentThesisType.EVENT_RISK,
                "近期事件是否改变未来经营或估值预期", status(eventRisk), DecisionImportance.HIGH,
                baselineCoverage(eventRisk),
                signals.stream().anyMatch(signal -> signal.thesis() == InvestmentThesisType.EVENT_RISK)
                        ? "已发现事件线索，但尚未形成能够判断方向和经营影响的一手证据"
                        : "当前画像没有能够判断近期事件方向和经营影响的一手内容",
                List.of(
                        need("近期新闻和公司公告线索", AnalysisCapability.EVENT_DISCOVERY,
                                EvidenceResolutionStatus.DISCOVERY_ONLY, "发现可能影响投资论点的近期事件"),
                        need("事件正文及其经营影响材料", AnalysisCapability.PRIMARY_EVENT_VERIFICATION,
                                EvidenceResolutionStatus.NOT_AVAILABLE, "确认事件方向和实际影响")),
                ConclusionSensitivity.HIGH));
        return List.copyOf(agenda);
    }

    /** 基线画像不是综合分析的停止条件；仍有高敏感度且可获取的证据时继续研究。 */
    private AnalysisReadiness analysisReadiness(
            StockFactorProfile profile, List<AnalysisAgendaItem> agenda) {
        boolean baselineComplete = profile.coverage().coveragePct().compareTo(BigDecimal.valueOf(60)) >= 0;
        List<String> open = agenda.stream()
                .filter(item -> item.conclusionSensitivity() == ConclusionSensitivity.HIGH)
                .filter(item -> item.evidenceCoverage() != EvidenceCoverage.SUFFICIENT)
                .filter(item -> item.evidenceNeeds().stream().anyMatch(need ->
                        need.resolutionStatus() == EvidenceResolutionStatus.AVAILABLE
                                || need.resolutionStatus() == EvidenceResolutionStatus.DISCOVERY_ONLY))
                .map(AnalysisAgendaItem::id)
                .toList();
        boolean stopEligible = baselineComplete && open.isEmpty();
        String reason = !baselineComplete
                ? "基础因子覆盖不足"
                : stopEligible
                        ? "高敏感度投资议题均已具备充分证据或不存在可获取的增量证据"
                        : "仍有高敏感度投资议题存在可获取的增量证据";
        return new AnalysisReadiness(baselineComplete, stopEligible, open, reason);
    }

    private AnalysisEvidenceNeed need(
            String evidence,
            AnalysisCapability capability,
            EvidenceResolutionStatus status,
            String contribution) {
        return new AnalysisEvidenceNeed(evidence, capability, status, contribution);
    }

    private boolean hasSignals(List<BusinessSignal> signals, String... ids) {
        for (String id : ids) {
            if (signals.stream().noneMatch(signal -> id.equals(signal.id()))) return false;
        }
        return true;
    }

    private ThesisStatus status(InvestmentThesis thesis) {
        return thesis == null ? ThesisStatus.UNRESOLVED : thesis.status();
    }

    private EvidenceCoverage baselineCoverage(InvestmentThesis thesis) {
        if (thesis == null || thesis.status() == ThesisStatus.UNRESOLVED) return EvidenceCoverage.INSUFFICIENT;
        if (thesis.status() == ThesisStatus.MIXED || !thesis.unresolvedSignalIds().isEmpty()) {
            return EvidenceCoverage.PARTIAL;
        }
        // 因子画像能够形成基线判断，但不能单独证明持续性和价格安全边际。
        return EvidenceCoverage.PARTIAL;
    }

    private ConclusionSensitivity sensitivity(InvestmentThesis thesis) {
        if (thesis == null || thesis.status() == ThesisStatus.UNRESOLVED
                || thesis.status() == ThesisStatus.MIXED || thesis.status() == ThesisStatus.ADVERSE
                || !thesis.unresolvedSignalIds().isEmpty()) {
            return ConclusionSensitivity.HIGH;
        }
        return ConclusionSensitivity.MEDIUM;
    }

    /** 历史分位描述当前估值位置；同行数据只作为参照，不承担严格可比结论。 */
    private void addValuationSignals(StockFactorProfile profile, List<BusinessSignal> signals) {
        FactorValue pe = factor(profile, FactorCategory.VALUATION, "pe_ttm");
        FactorValue percentile = factor(profile, FactorCategory.VALUATION, "pe_ttm_percentile_3y");
        if (!usable(percentile)) {
            signals.add(signal("valuation_history", InvestmentThesisType.VALUATION,
                    SignalDirection.UNKNOWN, SignalStrength.MEDIUM,
                    "历史估值位置暂时无法确认", "三年历史分位数据不可用",
                    "需要足够的同口径历史估值样本", List.of()));
        } else {
            SignalDirection direction = percentile.rawValue().compareTo(LOW_PERCENTILE) <= 0
                    ? SignalDirection.POSITIVE
                    : percentile.rawValue().compareTo(HIGH_PERCENTILE) >= 0
                            ? SignalDirection.NEGATIVE : SignalDirection.NEUTRAL;
            String position = direction == SignalDirection.POSITIVE ? "偏低"
                    : direction == SignalDirection.NEGATIVE ? "偏高" : "中部";
            List<AnalysisEvidence> evidence = new ArrayList<>();
            if (usable(pe)) evidence.add(evidence(pe, "当前TTM市盈率为" + number(pe) + "倍", "TTM"));
            evidence.add(evidence(percentile,
                    "当前TTM市盈率处于近三年" + number(percentile) + "%分位", "近三年同口径历史分位"));
            signals.add(signal("valuation_history", InvestmentThesisType.VALUATION,
                    direction, SignalStrength.STRONG,
                    "当前估值处于近三年历史区间的" + position,
                    "历史分位直接反映当前估值相对自身历史的位置",
                    "历史位置不等同于严格可比公司估值结论", evidence));
        }

        FactorValue peerPe = factor(profile, FactorCategory.VALUATION, "peer_pe_premium_pct");
        if (usable(peerPe)) {
            SignalDirection direction = peerPe.rawValue().compareTo(BigDecimal.valueOf(-20)) <= 0
                    ? SignalDirection.POSITIVE
                    : peerPe.rawValue().compareTo(BigDecimal.valueOf(20)) >= 0
                            ? SignalDirection.NEGATIVE : SignalDirection.NEUTRAL;
            signals.add(signal("valuation_peer_context", InvestmentThesisType.VALUATION,
                    direction, SignalStrength.WEAK,
                    "TTM市盈率相对行业样本中位数偏离" + signed(peerPe) + "%",
                    "机械同行样本可以提供估值背景",
                    "行业成分股尚未按商业模式和盈利结构做严格可比筛选",
                    List.of(evidence(peerPe, "TTM市盈率相对行业样本中位数偏离" + signed(peerPe) + "%",
                            "目标TTM PE与行业样本TTM PE中位数比较"))));
        }
    }

    /** 增长水平和增长动量分开表达，避免把“仍在增长”与“增速下降”混为一谈。 */
    private void addGrowthSignals(StockFactorProfile profile, List<BusinessSignal> signals) {
        FactorValue revenue = factor(profile, FactorCategory.GROWTH, "latest_quarter_revenue_yoy_pct");
        FactorValue profit = factor(profile, FactorCategory.GROWTH, "latest_quarter_net_profit_yoy_pct");
        if (usable(revenue) && usable(profit)) {
            SignalDirection direction = revenue.rawValue().signum() > 0 && profit.rawValue().signum() > 0
                    ? SignalDirection.POSITIVE
                    : revenue.rawValue().signum() < 0 && profit.rawValue().signum() < 0
                            ? SignalDirection.NEGATIVE : SignalDirection.NEUTRAL;
            signals.add(signal("growth_level", InvestmentThesisType.GROWTH, direction,
                    SignalStrength.STRONG,
                    "最新报告期营收同比" + signed(revenue) + "%，净利润同比" + signed(profit) + "%",
                    "同一报告期的收入和利润同比用于判断当前增长水平",
                    "增长水平本身不说明利润来源和未来持续时间",
                    List.of(evidence(revenue, "营收同比" + signed(revenue) + "%", "同报告期同比"),
                            evidence(profit, "净利润同比" + signed(profit) + "%", "同报告期同比"))));
        }

        FactorValue change = factor(profile, FactorCategory.GROWTH, "comparable_growth_change_pct");
        if (usable(change)) {
            SignalDirection direction = change.rawValue().compareTo(MATERIAL_GROWTH_CHANGE.negate()) <= 0
                    ? SignalDirection.NEGATIVE
                    : change.rawValue().compareTo(MATERIAL_GROWTH_CHANGE) >= 0
                            ? SignalDirection.POSITIVE : SignalDirection.NEUTRAL;
            signals.add(signal("growth_momentum", InvestmentThesisType.GROWTH, direction,
                    SignalStrength.MEDIUM,
                    "净利润同比增速较上年同报告期变化" + signed(change) + "个百分点",
                    "只使用上年同一报告期同比计算增长动量",
                    "增速变化不能单独证明经营拐点或解释变化原因",
                    List.of(evidence(change,
                            "净利润同比增速较上年同报告期变化" + signed(change) + "个百分点",
                            "最新同比减上年同报告期同比"))));
        }
    }

    /** 盈利质量只在归因和现金证据充分时形成方向，缺失数据保持为未知。 */
    private void addEarningsQualitySignals(StockFactorProfile profile, List<BusinessSignal> signals) {
        FactorValue annualRevenue = factor(profile, FactorCategory.GROWTH, "latest_annual_revenue_yoy_pct");
        FactorValue annualProfit = factor(profile, FactorCategory.GROWTH, "latest_annual_net_profit_yoy_pct");
        FactorValue nonRecurring = factor(profile, FactorCategory.QUALITY, "non_recurring_profit_ratio_pct");
        FactorValue cash = factor(profile, FactorCategory.QUALITY, "cash_to_net_profit_ratio");
        boolean materialGap = usable(annualRevenue) && usable(annualProfit)
                && annualProfit.rawValue().subtract(annualRevenue.rawValue()).abs()
                .compareTo(MATERIAL_PROFIT_REVENUE_GAP) >= 0;

        if (materialGap && !usable(nonRecurring)) {
            signals.add(signal("earnings_attribution", InvestmentThesisType.EARNINGS_QUALITY,
                    SignalDirection.UNKNOWN, SignalStrength.STRONG,
                    "年度利润增速与收入增速差异较大，利润增长来源尚未拆分",
                    "现有数据缺少扣非利润或非经常性损益占比",
                    "只能确认收入与利润增速不一致，不能推断差异来自何种原因",
                    List.of(evidence(annualRevenue, "年度营收同比" + signed(annualRevenue) + "%", "年度同比"),
                            evidence(annualProfit, "年度净利润同比" + signed(annualProfit) + "%", "年度同比"))));
        }
        if (usable(cash)) {
            SignalDirection direction = cash.rawValue().compareTo(BigDecimal.ONE) >= 0
                    ? SignalDirection.POSITIVE
                    : cash.rawValue().compareTo(MIN_CASH_CONVERSION) < 0
                            ? SignalDirection.NEGATIVE : SignalDirection.NEUTRAL;
            signals.add(signal("cash_conversion", InvestmentThesisType.EARNINGS_QUALITY,
                    direction, SignalStrength.MEDIUM,
                    "经营现金流与归母净利润比为" + number(cash),
                    "现金利润比用于观察当期利润的现金覆盖程度",
                    "单一报告期现金转化不能替代长期盈利质量分析",
                    List.of(evidence(cash, "经营现金流与归母净利润比为" + number(cash),
                            "经营现金流/归母净利润"))));
        }
    }

    /** 机构预测属于预期信号，单独成论点，避免与已实现财务事实混用。 */
    private void addExpectationSignals(StockFactorProfile profile, List<BusinessSignal> signals) {
        FactorCategoryResult result = profile.categories().get(FactorCategory.EXPECTATION_REVISION);
        if (result == null || result.signals().isEmpty()) return;
        String joined = String.join("；", result.signals());
        SignalDirection direction = containsAny(joined, "UPGRADED", "STABLE_OR_GROWING")
                ? SignalDirection.POSITIVE
                : containsAny(joined, "DOWNGRADED", "TURNING_NEGATIVE")
                        ? SignalDirection.NEGATIVE : SignalDirection.NEUTRAL;
        signals.add(signal("market_expectations", InvestmentThesisType.EXPECTATIONS,
                direction, SignalStrength.WEAK,
                "机构预测与评级形成的市场预期为" + expectationText(joined),
                "机构一致预期反映当前市场判断及预期方向",
                "机构预测属于观点和预测，不是已经实现的经营事实", List.of()));
    }

    /** 对尚未形成专门业务解释的维度，以分类得分生成弱信号而非强结论。 */
    private void addCategorySignal(
            StockFactorProfile profile,
            List<BusinessSignal> signals,
            FactorCategory category,
            InvestmentThesisType thesis,
            String id,
            String label) {
        FactorScore score = profile.categoryScores().get(category);
        if (score == null || score.score() == null) return;
        SignalDirection direction = score.score().compareTo(BigDecimal.valueOf(65)) >= 0
                ? SignalDirection.POSITIVE
                : score.score().compareTo(BigDecimal.valueOf(40)) < 0
                        ? SignalDirection.NEGATIVE : SignalDirection.NEUTRAL;
        signals.add(signal(id, thesis, direction, SignalStrength.WEAK,
                label + "评分为" + score.score() + "分",
                "该维度由已覆盖因子按统一策略计算",
                "分类得分用于辅助权衡，不单独决定投资立场", List.of()));
    }

    /** 按论点聚合信号，保留正反证据和未知项，不把未知自动视为负面。 */
    private List<InvestmentThesis> buildTheses(
            StockFactorProfile profile, List<BusinessSignal> signals) {
        Map<InvestmentThesisType, List<BusinessSignal>> grouped = new EnumMap<>(InvestmentThesisType.class);
        for (BusinessSignal signal : signals) {
            grouped.computeIfAbsent(signal.thesis(), ignored -> new ArrayList<>()).add(signal);
        }
        List<InvestmentThesis> theses = new ArrayList<>();
        for (InvestmentThesisType type : InvestmentThesisType.values()) {
            List<BusinessSignal> values = grouped.getOrDefault(type, List.of());
            List<String> positive = ids(values, SignalDirection.POSITIVE);
            List<String> negative = ids(values, SignalDirection.NEGATIVE);
            List<String> unknown = ids(values, SignalDirection.UNKNOWN);
            ThesisStatus status = thesisStatus(values);
            BigDecimal confidence = thesisConfidence(profile, values, unknown);
            theses.add(new InvestmentThesis(type, status, confidence,
                    thesisConclusion(type, status, positive, negative, unknown),
                    positive, negative, unknown));
        }
        return theses;
    }

    private ThesisStatus thesisStatus(List<BusinessSignal> values) {
        int positive = weight(values, SignalDirection.POSITIVE);
        int negative = weight(values, SignalDirection.NEGATIVE);
        int neutral = weight(values, SignalDirection.NEUTRAL);
        int unknown = weight(values, SignalDirection.UNKNOWN);
        if (values.isEmpty() || (unknown > 0 && positive == 0 && negative == 0 && neutral == 0)) {
            return ThesisStatus.UNRESOLVED;
        }
        if (positive > 0 && negative > 0) return ThesisStatus.MIXED;
        if (unknown > 0 && (positive > 0 || negative > 0)) return ThesisStatus.MIXED;
        if (positive > negative && positive > neutral) return ThesisStatus.SUPPORTIVE;
        if (negative > positive && negative > neutral) return ThesisStatus.ADVERSE;
        return ThesisStatus.NEUTRAL;
    }

    /** 强信号权重大于弱信号，避免一个宽泛同行弱信号覆盖自身历史强中性信号。 */
    private int weight(List<BusinessSignal> values, SignalDirection direction) {
        return values.stream().filter(value -> value.direction() == direction)
                .mapToInt(value -> switch (value.strength()) {
                    case STRONG -> 3;
                    case MEDIUM -> 2;
                    case WEAK -> 1;
                }).sum();
    }

    private InvestmentStance stance(StockFactorProfile profile, List<InvestmentThesis> theses) {
        BigDecimal score = profile.overallScore();
        if (score == null || profile.coverage().coveragePct().compareTo(BigDecimal.valueOf(60)) < 0) {
            return InvestmentStance.INSUFFICIENT;
        }
        InvestmentThesis valuation = thesis(theses, InvestmentThesisType.VALUATION);
        InvestmentThesis growth = thesis(theses, InvestmentThesisType.GROWTH);
        InvestmentThesis quality = thesis(theses, InvestmentThesisType.EARNINGS_QUALITY);
        long supportive = theses.stream().filter(value -> value.status() == ThesisStatus.SUPPORTIVE).count();
        long adverse = theses.stream().filter(value -> value.status() == ThesisStatus.ADVERSE).count();
        boolean qualityUnresolved = quality != null && !quality.unresolvedSignalIds().isEmpty();

        // 高分本身不是积极立场的充分条件，估值和基本面论点需要同时提供支持。
        if (score.compareTo(BigDecimal.valueOf(70)) >= 0) {
            boolean valueSupported = valuation != null && valuation.status() == ThesisStatus.SUPPORTIVE;
            boolean fundamentalSupported = (growth != null && growth.status() == ThesisStatus.SUPPORTIVE)
                    || (quality != null && quality.status() == ThesisStatus.SUPPORTIVE);
            return valueSupported && fundamentalSupported && !qualityUnresolved && adverse == 0
                    ? InvestmentStance.ATTRACTIVE : InvestmentStance.WATCH;
        }
        if (score.compareTo(BigDecimal.valueOf(55)) >= 0) {
            return adverse >= 2 ? InvestmentStance.NEUTRAL : InvestmentStance.WATCH;
        }
        if (score.compareTo(BigDecimal.valueOf(40)) >= 0) {
            return adverse >= 3 ? InvestmentStance.CAUTIOUS
                    : supportive >= 3 && valuation != null && valuation.status() != ThesisStatus.ADVERSE
                            ? InvestmentStance.WATCH : InvestmentStance.NEUTRAL;
        }
        return InvestmentStance.CAUTIOUS;
    }

    private InvestmentThesis thesis(List<InvestmentThesis> theses, InvestmentThesisType type) {
        return theses.stream().filter(value -> value.type() == type).findFirst().orElse(null);
    }

    private BigDecimal confidence(StockFactorProfile profile, List<InvestmentThesis> theses) {
        long unresolved = theses.stream().filter(thesis ->
                thesis.status() == ThesisStatus.UNRESOLVED || !thesis.unresolvedSignalIds().isEmpty()).count();
        BigDecimal base = profile.coverage().coveragePct().divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return base.subtract(BigDecimal.valueOf(unresolved).multiply(BigDecimal.valueOf(0.05)))
                .max(BigDecimal.valueOf(0.2)).min(BigDecimal.valueOf(0.95));
    }

    private List<UnresolvedIssue> unresolvedIssues(
            StockFactorProfile profile, List<BusinessSignal> signals) {
        Map<String, UnresolvedIssue> issues = new java.util.LinkedHashMap<>();
        for (String gap : profile.assessmentGuardrails().requiredGaps()) {
            UnresolvedIssue issue = switch (gap) {
                case "EARNINGS_ATTRIBUTION_UNRESOLVED" -> new UnresolvedIssue(
                        "earnings_attribution", InvestmentThesisType.EARNINGS_QUALITY,
                        "利润增长来源尚未确认", "扣非净利润或非经常性损益占比",
                        EvidenceResolutionStatus.NOT_AVAILABLE,
                        "现有结构化财报工具未提供扣非净利润字段");
                case "COMPARABLE_PEER_SET_NOT_VALIDATED" -> new UnresolvedIssue(
                        "strict_peer_validation", InvestmentThesisType.VALUATION,
                        "严格可比公司组尚未验证", "按商业模式和盈利结构筛选的可比公司组",
                        EvidenceResolutionStatus.NOT_AVAILABLE,
                        "现有同行工具提供行业样本，未提供严格商业可比筛选");
                case "ANNUAL_ROE_EVIDENCE_UNAVAILABLE" -> new UnresolvedIssue(
                        "annual_roe", InvestmentThesisType.EARNINGS_QUALITY,
                        "年度ROE证据尚不完整", "年度归母净利润与平均归母权益",
                        EvidenceResolutionStatus.NOT_AVAILABLE,
                        "当前画像仅取得季度ROE口径");
                case "RECENT_EVENT_IMPACT_NOT_OFFICIALLY_VERIFIED" -> new UnresolvedIssue(
                        "recent_event_impact", InvestmentThesisType.EVENT_RISK,
                        "近期重大事件影响尚未核实", "相关公告正文及事件影响材料",
                        EvidenceResolutionStatus.DISCOVERY_ONLY,
                        "新闻和公告检索可以发现材料，正文未读取时只更新发现状态");
                default -> new UnresolvedIssue(gap.toLowerCase(), InvestmentThesisType.EVENT_RISK,
                        gap, "对应的一手证据", EvidenceResolutionStatus.NOT_AVAILABLE,
                        "当前未登记解决能力");
            };
            issues.put(issue.id(), issue);
        }
        for (BusinessSignal signal : signals) {
            if (signal.direction() == SignalDirection.UNKNOWN && !signal.boundary().isBlank()
                    && !issues.containsKey(signal.id())) {
                issues.put(signal.id(), new UnresolvedIssue(
                        signal.id(), signal.thesis(), signal.summary(), signal.boundary(),
                        EvidenceResolutionStatus.NOT_AVAILABLE, signal.rationale()));
            }
        }
        return List.copyOf(issues.values());
    }

    private List<String> changeConditions(List<InvestmentThesis> theses) {
        List<String> conditions = new ArrayList<>();
        for (InvestmentThesis thesis : theses) {
            if (thesis.type() == InvestmentThesisType.VALUATION && thesis.status() != ThesisStatus.SUPPORTIVE) {
                conditions.add("估值进入更有安全边际的区间，或严格可比估值验证后，投资立场可能改善");
            }
            if (thesis.type() == InvestmentThesisType.GROWTH
                    && (thesis.status() == ThesisStatus.ADVERSE || thesis.status() == ThesisStatus.MIXED)) {
                conditions.add("后续同报告期收入和利润增速重新改善，将增强增长论点");
            }
            if (thesis.type() == InvestmentThesisType.EARNINGS_QUALITY
                    && !thesis.unresolvedSignalIds().isEmpty()) {
                conditions.add("扣非利润和现金流持续性得到确认后，可提高结论置信度");
            }
            if (thesis.type() == InvestmentThesisType.EVENT_RISK
                    && thesis.status() == ThesisStatus.UNRESOLVED) {
                conditions.add("公告正文确认重大事件影响后，需要重新评估事件风险论点");
            }
        }
        return conditions.stream().distinct().toList();
    }

    private String judgment(InvestmentStance stance) {
        return switch (stance) {
            case ATTRACTIVE -> "当前证据支持较积极的价值判断";
            case WATCH -> "当前具有观察价值，是否进一步配置取决于关键论点能否得到确认";
            case NEUTRAL -> "当前正负因素大致平衡，收益风险比尚不突出";
            case CAUTIOUS -> "当前不利因素占优，应保持谨慎";
            case INSUFFICIENT -> "当前关键证据不足，暂时无法形成方向性价值判断";
        };
    }

    private String thesisConclusion(
            InvestmentThesisType type,
            ThesisStatus status,
            List<String> positive,
            List<String> negative,
            List<String> unknown) {
        String label = switch (type) {
            case VALUATION -> "估值";
            case GROWTH -> "增长";
            case EARNINGS_QUALITY -> "盈利质量";
            case EXPECTATIONS -> "市场预期";
            case EVENT_RISK -> "事件风险";
            case PRICE_RISK -> "价格风险";
            case SHAREHOLDER_RETURN -> "股东回报";
        };
        String result;
        if (!negative.isEmpty() && !unknown.isEmpty() && positive.isEmpty()) {
            result = "存在负面信号，且关键证据仍未解决";
        } else if (!positive.isEmpty() && !unknown.isEmpty() && negative.isEmpty()) {
            result = "存在正面信号，但关键证据仍未解决";
        } else {
            result = switch (status) {
            case SUPPORTIVE -> "对当前投资判断形成支持";
            case ADVERSE -> "对当前投资判断形成拖累";
            case MIXED -> "同时存在正面和负面信号";
            case NEUTRAL -> "当前信号总体中性";
            case UNRESOLVED -> "当前证据不足以判断";
            };
        }
        return label + result;
    }

    private BigDecimal thesisConfidence(
            StockFactorProfile profile, List<BusinessSignal> values, List<String> unknown) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal coverage = profile.coverage().coveragePct()
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return unknown.isEmpty() ? coverage : coverage.subtract(BigDecimal.valueOf(0.15)).max(BigDecimal.ZERO);
    }

    private List<String> ids(List<BusinessSignal> values, SignalDirection direction) {
        return values.stream().filter(value -> value.direction() == direction)
                .map(BusinessSignal::id).toList();
    }

    private BusinessSignal signal(
            String id,
            InvestmentThesisType thesis,
            SignalDirection direction,
            SignalStrength strength,
            String summary,
            String rationale,
            String boundary,
            List<AnalysisEvidence> evidence) {
        return new BusinessSignal(id, thesis, direction, strength, summary, rationale, boundary, evidence);
    }

    private AnalysisEvidence evidence(FactorValue factor, String fact, String basis) {
        return new AnalysisEvidence(fact, "因子画像", factor.asOf(), factor.reportPeriod(), basis, factor.sources());
    }

    private FactorValue factor(StockFactorProfile profile, FactorCategory category, String name) {
        FactorCategoryResult result = profile.categories().get(category);
        if (result == null) return null;
        return result.factors().stream().filter(value -> name.equals(value.name())).findFirst().orElse(null);
    }

    private boolean usable(FactorValue value) {
        return value != null && value.usable() && value.rawValue() != null;
    }

    private String number(FactorValue value) {
        return value.rawValue().stripTrailingZeros().toPlainString();
    }

    private String signed(FactorValue value) {
        return (value.rawValue().signum() > 0 ? "+" : "") + number(value);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private String expectationText(String raw) {
        if (raw.contains("STRONGLY_UPGRADED")) return "明显上调";
        if (raw.contains("UPGRADED")) return "上调";
        if (raw.contains("STRONGLY_DOWNGRADED")) return "明显下调";
        if (raw.contains("DOWNGRADED")) return "下调";
        if (raw.contains("STABLE_OR_GROWING")) return "稳定或增长";
        return "中性或稳定";
    }
}
