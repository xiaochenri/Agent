package com.stockmind.application.dividend;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Cash dividend record. Eastmoney reports the cash amount on a per-ten-shares basis. */
public record DividendDistribution(
        LocalDate exDividendDate,
        BigDecimal cashPerTenShares,
        String progress) {
}
