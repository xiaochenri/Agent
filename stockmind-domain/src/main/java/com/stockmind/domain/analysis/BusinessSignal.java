package com.stockmind.domain.analysis;

import java.util.List;

/**
 * 原始指标经过口径校验后形成的业务信号。
 * summary说明“数据意味着什么”，boundary说明当前证据仍不能回答什么。
 */
public record BusinessSignal(
        String id,
        InvestmentThesisType thesis,
        SignalDirection direction,
        SignalStrength strength,
        String summary,
        String rationale,
        String boundary,
        List<AnalysisEvidence> evidence) {
    public BusinessSignal {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        boundary = boundary == null ? "" : boundary;
    }
}
