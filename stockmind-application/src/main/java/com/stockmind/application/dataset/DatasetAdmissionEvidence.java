package com.stockmind.application.dataset;

/** Evidence required before an external dataset may affect a factor. */
public record DatasetAdmissionEvidence(
        int liveSymbolsVerified,
        boolean fieldContractVerified,
        boolean temporalContractVerified,
        boolean crossSourceVerified,
        boolean stableErrorsVerified,
        boolean degradationVerified,
        boolean fixtureContractTested,
        boolean productionMetricsDefined) {
}
