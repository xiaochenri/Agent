package com.stockmind.domain.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A normalized real-time quote returned by a market data provider. */
public record MarketQuote(
        String instrumentId,
        String datasetId,
        String source,
        Instant asOf,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal dailyChangePct,
        BigDecimal volume,
        List<String> warnings) {
}
