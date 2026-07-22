package com.stockmind.domain.analysis;

/**
 * 尚未解决的投资分析问题。
 * requiredEvidence说明真正需要的数据，resolutionStatus帮助Agent判断继续查询是否有信息增益。
 */
public record UnresolvedIssue(
        String id,
        InvestmentThesisType thesis,
        String question,
        String requiredEvidence,
        EvidenceResolutionStatus resolutionStatus,
        String capabilityNote) { }
