package com.stockmind.domain.market;

import java.time.Instant;
import java.util.List;

public record BarDataset(
        String datasetId,
        List<MarketBar> bars,
        String source,
        Instant asOf,
        List<String> warnings) {
}
