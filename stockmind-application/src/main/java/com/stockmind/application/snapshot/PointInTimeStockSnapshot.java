package com.stockmind.application.snapshot;

import com.stockmind.application.dividend.DividendDistribution;
import com.stockmind.application.research.AnalystReport;
import com.stockmind.application.risk.SupplementalRiskSnapshot;
import com.stockmind.domain.instrument.Instrument;
import com.stockmind.domain.market.BarDataset;
import com.stockmind.domain.market.MarketPriceSnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Immutable request-level input shared by all factor calculators. */
public record PointInTimeStockSnapshot(
        String requestId,
        Instrument instrument,
        LocalDate requestedAsOf,
        MarketPriceSnapshot marketPrice,
        BarDataset marketHistory,
        BarDataset benchmarkHistory,
        IndustryPeerSnapshot industryPeers,
        SupplementalRiskSnapshot supplementalRisks,
        FinancialSnapshot financials,
        List<AnalystReport> analystReports,
        List<DividendDistribution> dividends,
        int filteredFutureResearchRecords,
        int filteredFutureDividendRecords,
        Map<String, String> providerFailures) {

    public PointInTimeStockSnapshot {
        analystReports = analystReports == null ? List.of() : List.copyOf(analystReports);
        dividends = dividends == null ? List.of() : List.copyOf(dividends);
        providerFailures = providerFailures == null ? Map.of() : Map.copyOf(providerFailures);
    }
}
