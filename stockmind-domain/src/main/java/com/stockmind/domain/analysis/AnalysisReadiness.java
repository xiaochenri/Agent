package com.stockmind.domain.analysis;

import java.util.List;

/** 基线画像完成后，综合投资分析是否已经具备停止条件。 */
public record AnalysisReadiness(
        boolean baselineComplete,
        boolean stopEligible,
        List<String> openHighSensitivityAgendaIds,
        String reason) {
    public AnalysisReadiness {
        openHighSensitivityAgendaIds = openHighSensitivityAgendaIds == null
                ? List.of() : List.copyOf(openHighSensitivityAgendaIds);
        reason = reason == null ? "" : reason;
    }
}
