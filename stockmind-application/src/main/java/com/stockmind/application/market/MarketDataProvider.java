package com.stockmind.application.market;

import com.stockmind.domain.market.BarDataset;
import com.stockmind.domain.market.MarketQuote;

public interface MarketDataProvider {
    MarketQuote loadQuote(String instrumentId);

    BarDataset loadBars(HistoricalBarsQuery query);
}
