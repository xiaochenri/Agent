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
        String name,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal dailyChangeAmount,
        BigDecimal dailyChangePct,
        BigDecimal volume,
        BigDecimal amount,
        BigDecimal turnoverPct,
        BigDecimal peTtm,
        BigDecimal pb,
        BigDecimal marketCapYi,
        BigDecimal floatMarketCapYi,
        BigDecimal limitUp,
        BigDecimal limitDown,
        BigDecimal volumeRatio,
        List<String> warnings) {
}
