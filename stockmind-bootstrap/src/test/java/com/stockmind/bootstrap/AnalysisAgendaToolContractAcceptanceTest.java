package com.stockmind.bootstrap;

import com.stockmind.bootstrap.business.tool.FactorAnalysisTools;

import com.stockmind.domain.analysis.AnalysisAgendaItem;
import com.stockmind.domain.analysis.AnalysisCapability;
import com.stockmind.domain.analysis.AnalysisEvidenceNeed;
import com.stockmind.domain.analysis.ConclusionSensitivity;
import com.stockmind.domain.analysis.DecisionImportance;
import com.stockmind.domain.analysis.EvidenceCoverage;
import com.stockmind.domain.analysis.EvidenceResolutionStatus;
import com.stockmind.domain.analysis.InvestmentThesisType;
import com.stockmind.domain.analysis.ThesisStatus;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** 验证分析议题通过能力候选连接工具，而不是输出唯一固定工具指令。 */
public final class AnalysisAgendaToolContractAcceptanceTest {
    public static void main(String[] args) throws Exception {
        FactorAnalysisTools tools = new FactorAnalysisTools(null, null);
        AnalysisAgendaItem item = new AnalysisAgendaItem(
                "valuation_safety_margin",
                InvestmentThesisType.VALUATION,
                "当前价格是否具有足够安全边际",
                ThesisStatus.NEUTRAL,
                DecisionImportance.HIGH,
                EvidenceCoverage.PARTIAL,
                "历史位置与行业参照含义不完全一致",
                List.of(
                        new AnalysisEvidenceNeed(
                                "前向PE", AnalysisCapability.FORWARD_VALUATION,
                                EvidenceResolutionStatus.AVAILABLE, "判断预期盈利要求"),
                        new AnalysisEvidenceNeed(
                                "严格可比公司", AnalysisCapability.STRICT_PEER_VALIDATION,
                                EvidenceResolutionStatus.NOT_AVAILABLE, "验证行业折价")),
                ConclusionSensitivity.HIGH);

        Method mapper = FactorAnalysisTools.class.getDeclaredMethod("agendaMap", AnalysisAgendaItem.class);
        mapper.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> mapped = (Map<String, Object>) mapper.invoke(tools, item);
        require("HIGH".equals(mapped.get("decision_weight"))
                        && "HIGH".equals(mapped.get("conclusion_sensitivity")),
                "工具协议缺少议题权重或结论敏感度");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> needs = (List<Map<String, Object>>) mapped.get("evidence_needs");
        require(needs.stream().anyMatch(need ->
                        "FORWARD_VALUATION".equals(need.get("capability"))
                                && List.of("analyst_consensus_forecast").equals(need.get("candidate_tools"))),
                "前向估值能力没有映射到候选工具");
        require(needs.stream().anyMatch(need ->
                        "STRICT_PEER_VALIDATION".equals(need.get("capability"))
                                && List.of().equals(need.get("candidate_tools"))),
                "未实现能力不应伪造候选工具");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
