package com.stockmind.application.sector;

import java.math.BigDecimal;

/** Current-day sector membership and performance for a stock. */
public record SectorSnapshot(
        String code,
        String name,
        BigDecimal dailyChangePct,
        String leader) {
}
