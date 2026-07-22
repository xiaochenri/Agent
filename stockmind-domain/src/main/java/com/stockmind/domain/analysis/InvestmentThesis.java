package com.stockmind.domain.analysis;

import java.math.BigDecimal;
import java.util.List;

/** 将同一主题下的正面、负面和未知信号汇总成可讨论的投资论点。 */
public record InvestmentThesis(
        InvestmentThesisType type,
        ThesisStatus status,
        BigDecimal confidence,
        String conclusion,
        List<String> supportingSignalIds,
        List<String> opposingSignalIds,
        List<String> unresolvedSignalIds) {
    public InvestmentThesis {
        supportingSignalIds = List.copyOf(supportingSignalIds);
        opposingSignalIds = List.copyOf(opposingSignalIds);
        unresolvedSignalIds = List.copyOf(unresolvedSignalIds);
    }
}
