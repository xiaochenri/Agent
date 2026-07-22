package com.stockmind.application.snapshot;

import java.math.BigDecimal;

/** Explicit conversions at the provider/application boundary. */
public final class CanonicalUnitNormalizer {
    private static final BigDecimal SHARES_PER_LOT = BigDecimal.valueOf(100);
    private static final BigDecimal YUAN_PER_YI = BigDecimal.valueOf(100_000_000L);
    private static final BigDecimal YUAN_PER_WAN = BigDecimal.valueOf(10_000L);

    private CanonicalUnitNormalizer() {}

    public static BigDecimal lotsToShares(BigDecimal lots) {
        return lots == null ? null : lots.multiply(SHARES_PER_LOT);
    }

    public static BigDecimal yiYuanToYuan(BigDecimal yiYuan) {
        return yiYuan == null ? null : yiYuan.multiply(YUAN_PER_YI);
    }

    public static BigDecimal wanYuanToYuan(BigDecimal wanYuan) {
        return wanYuan == null ? null : wanYuan.multiply(YUAN_PER_WAN);
    }

    public static BigDecimal percentToRatio(BigDecimal percent) {
        return percent == null ? null : percent.movePointLeft(2);
    }
}
