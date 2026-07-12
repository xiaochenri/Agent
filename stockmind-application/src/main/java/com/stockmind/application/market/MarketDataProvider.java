package com.stockmind.application.market;

import com.stockmind.domain.market.BarDataset;

public interface MarketDataProvider {
    BarDataset loadBars(HistoricalBarsQuery query);
}
