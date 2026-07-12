package com.stockmind.application.market;

import com.stockmind.domain.market.AdjustmentMode;
import com.stockmind.domain.market.BarInterval;
import java.time.LocalDate;

public record HistoricalBarsQuery(
        String instrumentId,
        BarInterval interval,
        LocalDate startDate,
        LocalDate endDate,
        AdjustmentMode adjustment) {
}
