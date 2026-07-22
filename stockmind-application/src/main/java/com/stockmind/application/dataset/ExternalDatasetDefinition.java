package com.stockmind.application.dataset;

import java.util.Objects;

/** Auditable registration of an external dataset before it may affect a factor score. */
public record ExternalDatasetDefinition(
        String provider,
        String dataset,
        String endpointClass,
        TemporalCapability temporalCapability,
        String unitContract,
        String rateLimitPolicy,
        String fallbackProvider,
        String factorUsage,
        AdmissionStatus admissionStatus) {

    public ExternalDatasetDefinition {
        provider = require(provider, "provider");
        dataset = require(dataset, "dataset");
        endpointClass = require(endpointClass, "endpointClass");
        temporalCapability = Objects.requireNonNull(temporalCapability, "temporalCapability");
        unitContract = require(unitContract, "unitContract");
        rateLimitPolicy = require(rateLimitPolicy, "rateLimitPolicy");
        factorUsage = require(factorUsage, "factorUsage");
        admissionStatus = Objects.requireNonNull(admissionStatus, "admissionStatus");
        fallbackProvider = fallbackProvider == null ? "" : fallbackProvider.trim();
        if (provider.equalsIgnoreCase(fallbackProvider)) {
            throw new IllegalArgumentException("备用源必须独立于主数据源");
        }
    }

    public boolean mayAffectScore(LocalDateContext context) {
        if (admissionStatus != AdmissionStatus.ADMITTED) return false;
        return temporalCapability == TemporalCapability.POINT_IN_TIME
                || (temporalCapability == TemporalCapability.CURRENT_ONLY && context.currentRequest());
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        return value.trim();
    }

    public record LocalDateContext(boolean currentRequest) {}
}
