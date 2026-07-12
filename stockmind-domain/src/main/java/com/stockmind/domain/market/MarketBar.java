package com.stockmind.domain.market;

import java.math.BigDecimal;
import java.time.Instant;

/** A normalized, closed OHLCV bar. Values are never rounded for indicator calculation. */
public record MarketBar(
        String instrumentId,
        BarInterval interval,
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal amount,
        AdjustmentMode adjustment) {
}
