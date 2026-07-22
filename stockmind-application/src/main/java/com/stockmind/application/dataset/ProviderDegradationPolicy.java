package com.stockmind.application.dataset;

public record ProviderDegradationPolicy(
        boolean retryOnRateLimit,
        boolean circuitBreakOnForbidden,
        boolean reduceFactorCoverageOnFailure,
        boolean failWholeProfileOnFailure) {

    public static ProviderDegradationPolicy stockFactorDefault() {
        return new ProviderDegradationPolicy(true, true, true, false);
    }
}
