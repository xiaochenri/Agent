package com.stockmind.domain.instrument;

import java.util.Objects;

/** Unambiguous, provider-independent identity for a traded instrument. */
public record Instrument(
        String normalizedSymbol,
        String securityCode,
        Exchange exchange,
        InstrumentType instrumentType,
        String name) {

    public Instrument {
        Objects.requireNonNull(normalizedSymbol, "normalizedSymbol");
        Objects.requireNonNull(securityCode, "securityCode");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(instrumentType, "instrumentType");
        name = name == null ? "" : name;
        if (!normalizedSymbol.equals(exchange.name() + securityCode)) {
            throw new IllegalArgumentException("normalizedSymbol必须由交易所和6位代码组成");
        }
        if (!securityCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("securityCode必须是6位数字");
        }
    }

    public Instrument withName(String resolvedName) {
        return new Instrument(normalizedSymbol, securityCode, exchange, instrumentType, resolvedName);
    }
}
