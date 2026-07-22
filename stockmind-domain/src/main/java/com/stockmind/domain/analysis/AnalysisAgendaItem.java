package com.stockmind.domain.analysis;

import java.util.List;

/**
 * 因子画像之后仍需研究的投资议题。
 *
 * <p>它描述问题、当前矛盾和证据需求，不直接规定下一步必须调用哪个工具。</p>
 */
public record AnalysisAgendaItem(
        String id,
        InvestmentThesisType thesis,
        String question,
        ThesisStatus currentJudgment,
        DecisionImportance decisionWeight,
        EvidenceCoverage evidenceCoverage,
        String contradiction,
        List<AnalysisEvidenceNeed> evidenceNeeds,
        ConclusionSensitivity conclusionSensitivity) {
    public AnalysisAgendaItem {
        id = id == null ? "" : id;
        question = question == null ? "" : question;
        contradiction = contradiction == null ? "" : contradiction;
        evidenceNeeds = evidenceNeeds == null ? List.of() : List.copyOf(evidenceNeeds);
    }
}
