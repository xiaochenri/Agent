package com.stockmind.domain.analysis;

/** 一个投资议题仍需要的证据，以及当前系统对该类证据的解决能力。 */
public record AnalysisEvidenceNeed(
        String evidence,
        AnalysisCapability capability,
        EvidenceResolutionStatus resolutionStatus,
        String expectedContribution) {
    public AnalysisEvidenceNeed {
        evidence = evidence == null ? "" : evidence;
        expectedContribution = expectedContribution == null ? "" : expectedContribution;
    }
}
