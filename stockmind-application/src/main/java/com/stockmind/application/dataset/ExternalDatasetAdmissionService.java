package com.stockmind.application.dataset;

import java.util.ArrayList;
import java.util.List;

public final class ExternalDatasetAdmissionService {
    /** Evaluates license, point-in-time, unit, identifier and degradation evidence before admission. */
    public DatasetAdmissionCheckResult check(
            ExternalDatasetDefinition definition, DatasetAdmissionEvidence evidence) {
        List<String> failed = new ArrayList<>();
        if (evidence.liveSymbolsVerified() < 3) failed.add("LIVE_TEST_THREE_SYMBOLS");
        if (!evidence.fieldContractVerified()) failed.add("FIELD_CONTRACT");
        if (!evidence.temporalContractVerified()) failed.add("TEMPORAL_CONTRACT");
        if (!evidence.crossSourceVerified()) failed.add("CROSS_SOURCE_VALIDATION");
        if (!evidence.stableErrorsVerified()) failed.add("STABLE_ERROR_MODEL");
        if (!evidence.degradationVerified()) failed.add("DEGRADATION_POLICY");
        if (!evidence.fixtureContractTested()) failed.add("FIXTURE_CONTRACT_TEST");
        if (!evidence.productionMetricsDefined()) failed.add("PRODUCTION_METRICS");
        if (definition.temporalCapability() == TemporalCapability.UNSUPPORTED) {
            failed.add("TEMPORAL_CAPABILITY_UNSUPPORTED");
        }
        return new DatasetAdmissionCheckResult(failed.isEmpty(), failed);
    }
}
