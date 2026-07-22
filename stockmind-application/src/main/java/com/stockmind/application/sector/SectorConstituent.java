package com.stockmind.application.sector;

import java.math.BigDecimal;

/** Industry constituent with normalized symbol and market capitalization in CNY yuan. */
public record SectorConstituent(String normalizedSymbol, String name, BigDecimal marketCapCny) {
}
