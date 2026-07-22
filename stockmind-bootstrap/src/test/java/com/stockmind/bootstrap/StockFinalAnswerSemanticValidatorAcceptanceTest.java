package com.stockmind.bootstrap;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.json.AgentJsonCodecUtil;
import java.util.List;
import java.util.Map;

/** 验证最终答案遵守业务层投资立场；可读论据映射由通用 evidence contract 负责。 */
public final class StockFinalAnswerSemanticValidatorAcceptanceTest {
    public static void main(String[] args) {
        var validator = new StockFinalAnswerSemanticValidator(new AgentJsonCodecUtil());
        List<AgentExecutionLogEntry> log = evidenceLog();

        String conclusion = "当前具有观察价值，但关键投资论点仍需确认。";
        Map<String, Object> missingContract = Map.of(
                "core_conclusions", List.of(conclusion),
                "conclusion_evidence", List.of(Map.of("conclusion", conclusion, "evidence", List.of())));
        List<String> issues = validator.validate(
                "分析一下药明康德最近是否具有投资价值", log, missingContract);
        require(issues.stream().anyMatch(value -> value.contains("investment_stance")),
                "未要求最终答案使用业务层投资立场");

        Map<String, Object> wrongStance = Map.of(
                "investment_stance", "ATTRACTIVE",
                "core_conclusions", List.of(conclusion),
                "conclusion_evidence", List.of(Map.of(
                        "conclusion", conclusion,
                        "analysis_signal_ids", List.of("growth_level"),
                        "evidence", List.of())));
        require(validator.validate("分析一下药明康德最近是否具有投资价值", log, wrongStance)
                        .stream().anyMatch(value -> value.contains("expected=WATCH")),
                "未拦截模型重新创造的投资立场");

        Map<String, Object> optionalAuditMetadata = Map.of(
                "investment_stance", "WATCH",
                "core_conclusions", List.of(conclusion),
                "conclusion_evidence", List.of(Map.of(
                        "conclusion", conclusion,
                        "analysis_signal_ids", List.of("invented_signal"),
                        "evidence", List.of())));
        require(validator.validate(
                        "分析一下药明康德最近是否具有投资价值", log, optionalAuditMetadata)
                .isEmpty(), "可选审计编号不应阻断已有可读证据链的最终回答");

        Map<String, Object> valid = Map.of(
                "investment_stance", "WATCH",
                "core_conclusions", List.of(conclusion),
                "conclusion_evidence", List.of(Map.of(
                        "conclusion", conclusion,
                        "evidence", List.of(Map.of(
                                "fact", "当前估值处于历史中部，经营仍增长但同报告期增速下降。",
                                "source_step", "tool_call_round_1",
                                "source_type", "因子画像",
                                "as_of", "2026-07-21",
                                "basis", "TTM历史分位及同报告期同比",
                                "analysis_signal_ids", List.of(
                                        "valuation_history", "growth_level", "growth_momentum"))))));
        require(validator.validate("分析一下药明康德最近是否具有投资价值", log, valid).isEmpty(),
                "正确引用业务分析中间层的结论被拒绝");

        String watchConclusion = "药明康德当前投资立场为WATCH（观察），现阶段具有观察价值。";
        Map<String, Object> logCompatible = Map.of(
                "core_conclusions", List.of(watchConclusion),
                "conclusion_evidence", List.of(Map.of(
                        "conclusion", watchConclusion,
                        "evidence", List.of(Map.of(
                                "fact", "当前估值处于历史中部，经营仍增长但增速下降。",
                                "source_step", "tool_call_round_1",
                                "source_type", "因子画像",
                                "as_of", "2026-07-21",
                                "basis", "TTM历史分位及同报告期同比",
                                "analysis_signal_ids", List.of(
                                        "valuation_history", "growth_level", "growth_momentum"))))));
        require(validator.validate("分析一下药明康德最近是否具有投资价值", log, logCompatible).isEmpty(),
                "第一条结论已明确立场且论据级引用信号时应通过校验");

        Map<String, Object> readableEvidenceWithoutIds = Map.of(
                "investment_stance", "WATCH",
                "core_conclusions", List.of(conclusion),
                "conclusion_evidence", List.of(Map.of(
                        "conclusion", conclusion,
                        "evidence", List.of(Map.of(
                                "fact", "当前估值处于历史中部，经营仍增长但增速下降。",
                                "source_step", "tool_call_round_1",
                                "source_type", "因子画像",
                                "as_of", "2026-07-21",
                                "basis", "TTM历史分位及同报告期同比")))));
        require(validator.validate(
                        "分析一下药明康德最近是否具有投资价值", log, readableEvidenceWithoutIds)
                .isEmpty(), "具有完整可读论据的数据不应因缺少内部 signal_id 而空转");
    }

    private static List<AgentExecutionLogEntry> evidenceLog() {
        Map<String, Object> analysis = Map.of(
                "stance", "WATCH",
                "signals", List.of(
                        Map.of("signal_id", "valuation_history"),
                        Map.of("signal_id", "growth_level"),
                        Map.of("signal_id", "growth_momentum")));
        return List.of(new AgentExecutionLogEntry(
                "tool_call_round_1", "stock_factor_profile", Map.of(),
                Map.of("status", "success", "validation_passed", true,
                        "data", Map.of("investment_analysis", analysis)), 0.9));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
