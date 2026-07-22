package com.stockmind.domain.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 股票业务分析中间层的完整输出。 */
public record StockInvestmentAnalysis(
        String symbol,
        String name,
        LocalDate asOf,
        InvestmentStance stance,
        BigDecimal confidence,
        String timeHorizon,
        String judgment,
        List<BusinessSignal> signals,
        List<InvestmentThesis> theses,
        List<AnalysisAgendaItem> analysisAgenda,
        AnalysisReadiness analysisReadiness,
        List<UnresolvedIssue> unresolvedIssues,
        List<String> unresolvedQuestions,
        List<String> changeConditions) {
    public StockInvestmentAnalysis {
        signals = List.copyOf(signals);
        theses = List.copyOf(theses);
        analysisAgenda = List.copyOf(analysisAgenda);
        unresolvedIssues = List.copyOf(unresolvedIssues);
        unresolvedQuestions = List.copyOf(unresolvedQuestions);
        changeConditions = List.copyOf(changeConditions);
    }
}
