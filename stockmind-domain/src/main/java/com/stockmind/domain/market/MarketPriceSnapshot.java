package com.stockmind.domain.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Point-in-time market values in canonical units: CNY, shares and CNY yuan. */
public record MarketPriceSnapshot(
        String normalizedSymbol,
        String instrumentName,
        LocalDate requestedAsOf,
        LocalDate effectiveTradeDate,
        PriceSourceType priceSourceType,
        BigDecimal priceCny,
        BigDecimal volumeShares,
        BigDecimal amountCny,
        BigDecimal turnoverRatio,
        BigDecimal marketCapCny,
        BigDecimal floatMarketCapCny,
        BigDecimal peTtm,
        BigDecimal pb,
        String source,
        String datasetId,
        List<String> warnings) {

    public MarketPriceSnapshot {
        instrumentName = instrumentName == null ? "" : instrumentName;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
