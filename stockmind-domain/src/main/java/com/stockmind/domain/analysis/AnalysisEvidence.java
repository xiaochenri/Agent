package com.stockmind.domain.analysis;

import java.time.LocalDate;
import java.util.List;

/** 支撑业务信号的标准化事实，保留日期、口径和来源以便最终答案追溯。 */
public record AnalysisEvidence(
        String fact,
        String sourceType,
        LocalDate asOf,
        LocalDate reportPeriod,
        String basis,
        List<String> sources) {
    public AnalysisEvidence {
        fact = fact == null ? "" : fact;
        sourceType = sourceType == null ? "" : sourceType;
        basis = basis == null ? "" : basis;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
