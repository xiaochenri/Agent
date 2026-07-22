package com.stockmind.application.dataset;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ExternalDatasetRegistry {
    private final Map<String, ExternalDatasetDefinition> definitions;

    public ExternalDatasetRegistry(Collection<ExternalDatasetDefinition> definitions) {
        Map<String, ExternalDatasetDefinition> indexed = new LinkedHashMap<>();
        for (ExternalDatasetDefinition definition : definitions) {
            String key = key(definition.provider(), definition.dataset());
            if (indexed.putIfAbsent(key, definition) != null) {
                throw new IllegalArgumentException("外部数据集重复登记: " + key);
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    /** Finds an admitted dataset by its stable provider and dataset identifiers. */
    public Optional<ExternalDatasetDefinition> find(String provider, String dataset) {
        return Optional.ofNullable(definitions.get(key(provider, dataset)));
    }

    /** Returns an immutable view of all registered definitions. */
    public Collection<ExternalDatasetDefinition> all() {
        return definitions.values();
    }

    /** Builds the version-one allowlist used by the stock factor pipeline. */
    public static ExternalDatasetRegistry stockFactorV1() {
        return new ExternalDatasetRegistry(java.util.List.of(
                definition("tencent", "realtime_quote", TemporalCapability.CURRENT_ONLY,
                        "price=CNY, volume=lots, amount=10k_CNY, turnover=percent, market_cap=100m_CNY",
                        "tencent-market-v1", "", "valuation", AdmissionStatus.ADMITTED),
                definition("tencent", "adjusted_daily_bars", TemporalCapability.POINT_IN_TIME,
                        "price=CNY, volume=lots", "tencent-market-v1", "10jqka", "momentum_risk", AdmissionStatus.ADMITTED),
                definition("sina", "financial_statements", TemporalCapability.POINT_IN_TIME,
                        "amount=CNY, ratio=source_declared", "sina-financial-v1", "10jqka", "quality_growth", AdmissionStatus.ADMITTED),
                definition("eastmoney", "analyst_research", TemporalCapability.POINT_IN_TIME,
                        "eps=CNY_per_share", "eastmoney-request-gate-v1", "10jqka", "expectation_revision", AdmissionStatus.ADMITTED),
                definition("eastmoney", "dividend_history", TemporalCapability.POINT_IN_TIME,
                        "cash=10_shares_CNY", "eastmoney-request-gate-v1", "", "shareholder_return", AdmissionStatus.ADMITTED),
                definition("eastmoney", "stock_profile", TemporalCapability.CURRENT_ONLY,
                        "shares=shares, market_cap=CNY", "eastmoney-request-gate-v1", "", "instrument_master", AdmissionStatus.CANDIDATE)));
    }

    private static ExternalDatasetDefinition definition(
            String provider, String dataset, TemporalCapability temporal, String units,
            String rateLimit, String fallback, String usage, AdmissionStatus status) {
        return new ExternalDatasetDefinition(provider, dataset, "PUBLIC_UNAUTHENTICATED", temporal,
                units, rateLimit, fallback, usage, status);
    }

    private static String key(String provider, String dataset) {
        if (provider == null || dataset == null) return "";
        return provider.trim().toLowerCase() + ":" + dataset.trim().toLowerCase();
    }
}
