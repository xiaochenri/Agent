package com.stockmind.application.snapshot;

import java.math.BigDecimal;

public record PeerMarketValue(
        String normalizedSymbol,
        String name,
        BigDecimal peTtm,
        BigDecimal pb,
        BigDecimal marketCapCny,
        String source) {
}
