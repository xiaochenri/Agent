package com.stockmind.application.snapshot;

import com.stockmind.application.dividend.DividendDistribution;
import com.stockmind.application.dividend.DividendProvider;
import com.stockmind.application.financial.FinancialReportProvider;
import com.stockmind.application.financial.FinancialStatementPeriod;
import com.stockmind.application.financial.FinancialStatementUnitNormalizer;
import com.stockmind.application.financial.CanonicalFinancialStatementPeriod;
import com.stockmind.application.instrument.InstrumentResolver;
import com.stockmind.application.market.HistoricalBarsQuery;
import com.stockmind.application.market.MarketDataNotFoundException;
import com.stockmind.application.market.MarketDataProvider;
import com.stockmind.application.research.AnalystReport;
import com.stockmind.application.research.AnalystResearchProvider;
import com.stockmind.application.sector.SectorDataProvider;
import com.stockmind.application.sector.SectorConstituentSet;
import com.stockmind.application.risk.SupplementalRiskProvider;
import com.stockmind.application.risk.SupplementalRiskSnapshot;
import com.stockmind.domain.instrument.Instrument;
import com.stockmind.domain.instrument.InstrumentType;
import com.stockmind.domain.market.AdjustmentMode;
import com.stockmind.domain.market.BarDataset;
import com.stockmind.domain.market.BarInterval;
import com.stockmind.domain.market.MarketBar;
import com.stockmind.domain.market.MarketPriceSnapshot;
import com.stockmind.domain.market.MarketQuote;
import com.stockmind.domain.market.PriceSourceType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds one point-in-time data snapshot per factor-profile request. */
public final class PointInTimeStockSnapshotService {
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> STATEMENT_TYPES = List.of("lrb", "fzb", "llb");

    private final InstrumentResolver instrumentResolver;
    private final MarketDataProvider marketDataProvider;
    private final FinancialReportProvider financialReportProvider;
    private final AnalystResearchProvider analystResearchProvider;
    private final DividendProvider dividendProvider;
    private final SectorDataProvider sectorDataProvider;
    private final SupplementalRiskProvider supplementalRiskProvider;
    private final FinancialStatementUnitNormalizer financialUnitNormalizer;
    private final Clock clock;

    /** Creates the production service without optional supplemental-risk data. */
    public PointInTimeStockSnapshotService(
            InstrumentResolver instrumentResolver,
            MarketDataProvider marketDataProvider,
            FinancialReportProvider financialReportProvider,
            AnalystResearchProvider analystResearchProvider,
            DividendProvider dividendProvider,
            SectorDataProvider sectorDataProvider) {
        this(instrumentResolver, marketDataProvider, financialReportProvider,
                analystResearchProvider, dividendProvider, sectorDataProvider, SupplementalRiskProvider.NONE, Clock.system(CHINA_ZONE));
    }

    /** Creates the production service with the current-only supplemental-risk provider. */
    public PointInTimeStockSnapshotService(InstrumentResolver instrumentResolver,MarketDataProvider marketDataProvider,FinancialReportProvider financialReportProvider,AnalystResearchProvider analystResearchProvider,DividendProvider dividendProvider,SectorDataProvider sectorDataProvider,SupplementalRiskProvider supplementalRiskProvider){this(instrumentResolver,marketDataProvider,financialReportProvider,analystResearchProvider,dividendProvider,sectorDataProvider,supplementalRiskProvider,Clock.system(CHINA_ZONE));}

    /** Test-friendly constructor with an explicit clock and no supplemental-risk provider. */
    public PointInTimeStockSnapshotService(
            InstrumentResolver instrumentResolver,
            MarketDataProvider marketDataProvider,
            FinancialReportProvider financialReportProvider,
            AnalystResearchProvider analystResearchProvider,
            DividendProvider dividendProvider,
            SectorDataProvider sectorDataProvider,
            Clock clock) {
        this(instrumentResolver,marketDataProvider,financialReportProvider,analystResearchProvider,dividendProvider,sectorDataProvider,SupplementalRiskProvider.NONE,clock);
    }

    /** Fully explicit constructor used when both risk provider and clock must be controlled. */
    public PointInTimeStockSnapshotService(InstrumentResolver instrumentResolver,MarketDataProvider marketDataProvider,FinancialReportProvider financialReportProvider,AnalystResearchProvider analystResearchProvider,DividendProvider dividendProvider,SectorDataProvider sectorDataProvider,SupplementalRiskProvider supplementalRiskProvider,Clock clock) {
        this.instrumentResolver = instrumentResolver;
        this.marketDataProvider = marketDataProvider;
        this.financialReportProvider = financialReportProvider;
        this.analystResearchProvider = analystResearchProvider;
        this.dividendProvider = dividendProvider;
        this.sectorDataProvider = sectorDataProvider;
        this.supplementalRiskProvider = supplementalRiskProvider;
        this.financialUnitNormalizer = new FinancialStatementUnitNormalizer();
        this.clock = clock;
    }

    /**
     * Loads one internally consistent snapshot as of the requested China-market date.
     * Future bars, statements, research and dividends are filtered out; current-only peer
     * and supplemental-risk sources are explicitly unavailable for historical requests.
     */
    public PointInTimeStockSnapshot load(String symbol, LocalDate asOf) {
        if (asOf == null) throw new IllegalArgumentException("as_of不能为空");
        LocalDate today = LocalDate.now(clock.withZone(CHINA_ZONE));
        if (asOf.isAfter(today)) throw new IllegalArgumentException("as_of不能晚于当前日期");

        Instrument instrument = instrumentResolver.resolve(symbol, InstrumentType.STOCK);
        BarDataset marketHistory = loadMarketHistory(instrument, asOf);
        MarketPriceSnapshot price = loadPrice(instrument, asOf, today, marketHistory);
        Map<String, String> failures = new LinkedHashMap<>();
        BarDataset benchmarkHistory = loadBenchmarkHistory(asOf, failures);
        IndustryPeerSnapshot industryPeers = loadIndustryPeers(instrument, price, asOf, today, failures);
        SupplementalRiskSnapshot supplementalRisks = loadSupplementalRisks(instrument, asOf, today, failures);
        FinancialSnapshot financials = loadFinancials(instrument, asOf, failures);
        FilteredList<AnalystReport> research = loadResearch(instrument, asOf, failures);
        FilteredList<DividendDistribution> dividends = loadDividends(instrument, asOf, failures);

        String name = price.instrumentName().isBlank() ? instrument.name() : price.instrumentName();
        Instrument namedInstrument = instrument.withName(name);
        return new PointInTimeStockSnapshot(UUID.randomUUID().toString(), namedInstrument, asOf, price,
                marketHistory, benchmarkHistory, industryPeers, supplementalRisks, financials, research.values(), dividends.values(), research.filteredFuture(),
                dividends.filteredFuture(), failures);
    }

    // Supplemental-risk endpoints expose current state only; never backfill it into history.
    private SupplementalRiskSnapshot loadSupplementalRisks(Instrument instrument,LocalDate asOf,LocalDate today,Map<String,String>failures){if(!asOf.equals(today))return SupplementalRiskSnapshot.unavailable(asOf,"supplemental_risk_source_is_current_only");try{return supplementalRiskProvider.load(instrument.normalizedSymbol(),asOf);}catch(RuntimeException e){failures.put("supplemental_risks",stableFailure(e));return SupplementalRiskSnapshot.unavailable(asOf,"supplemental_risk_provider_failed");}}

    private BarDataset loadBenchmarkHistory(LocalDate asOf, Map<String, String> failures) {
        try {
        BarDataset loaded = marketDataProvider.loadBars(new HistoricalBarsQuery(
                "SH000300", BarInterval.DAY_1, asOf.minusYears(6), asOf, AdjustmentMode.NONE));
            List<MarketBar> accepted = loaded.bars().stream()
                    .filter(value -> !value.closeTime().atZone(CHINA_ZONE).toLocalDate().isAfter(asOf)).toList();
            return new BarDataset(loaded.datasetId(), accepted, loaded.source(), loaded.asOf(), loaded.warnings());
        } catch (RuntimeException e) {
            failures.put("benchmark_history", stableFailure(e));
            return null;
        }
    }

    private IndustryPeerSnapshot loadIndustryPeers(
            Instrument instrument, MarketPriceSnapshot subjectPrice, LocalDate asOf, LocalDate today,
            Map<String, String> failures) {
        if (!asOf.equals(today)) return IndustryPeerSnapshot.unavailableForHistory(asOf);
        try {
            SectorConstituentSet peers = sectorDataProvider.loadTopIndustryConstituents(
                    instrument.normalizedSymbol(), 10);
            List<PeerMarketValue> values = new ArrayList<>();
            List<String> limitations = new ArrayList<>();
            for (var peer : peers.constituents()) {
                try {
                    if (peer.normalizedSymbol().equals(instrument.normalizedSymbol())) {
                        values.add(new PeerMarketValue(peer.normalizedSymbol(), peer.name(), subjectPrice.peTtm(),
                                subjectPrice.pb(), subjectPrice.marketCapCny(), subjectPrice.source()));
                    } else {
                        MarketQuote quote = marketDataProvider.loadQuote(peer.normalizedSymbol());
                        values.add(new PeerMarketValue(peer.normalizedSymbol(), peer.name(), quote.peTtm(), quote.pb(),
                                CanonicalUnitNormalizer.yiYuanToYuan(quote.marketCapYi()), quote.source()));
                    }
                } catch (RuntimeException e) {
                    limitations.add("peer_quote_unavailable:" + peer.normalizedSymbol());
                }
            }
            return new IndustryPeerSnapshot(asOf, true, true, peers, values, limitations);
        } catch (RuntimeException e) {
            failures.put("industry_peers", stableFailure(e));
            return new IndustryPeerSnapshot(asOf, true, false, null, List.of(),
                    List.of("industry_peer_provider_failed"));
        }
    }

    private BarDataset loadMarketHistory(Instrument instrument, LocalDate asOf) {
        BarDataset loaded = marketDataProvider.loadBars(new HistoricalBarsQuery(
                instrument.normalizedSymbol(), BarInterval.DAY_1, asOf.minusYears(6), asOf,
                AdjustmentMode.FORWARD));
        List<MarketBar> accepted = loaded.bars().stream()
                .filter(value -> !value.closeTime().atZone(CHINA_ZONE).toLocalDate().isAfter(asOf))
                .toList();
        if (accepted.isEmpty()) {
            throw new MarketDataNotFoundException(
                    instrument.normalizedSymbol() + " 在 " + asOf + " 或之前没有可用日K");
        }
        List<String> warnings = new ArrayList<>(loaded.warnings());
        int filtered = loaded.bars().size() - accepted.size();
        if (filtered > 0) warnings.add("filtered_future_market_bars=" + filtered);
        return new BarDataset(loaded.datasetId(), accepted, loaded.source(), loaded.asOf(), warnings);
    }

    private MarketPriceSnapshot loadPrice(
            Instrument instrument, LocalDate asOf, LocalDate today, BarDataset dataset) {
        MarketBar latestBar = dataset.bars().stream()
                .filter(value -> !value.closeTime().atZone(CHINA_ZONE).toLocalDate().isAfter(asOf))
                .max(Comparator.comparing(MarketBar::closeTime))
                .orElseThrow(() -> new MarketDataNotFoundException(
                        instrument.normalizedSymbol() + " 在 " + asOf + " 或之前没有可用收盘价"));
        LocalDate latestTradeDate = latestBar.closeTime().atZone(CHINA_ZONE).toLocalDate();
        String quoteFallbackWarning = null;
        if (asOf.equals(today)) {
            try {
                MarketQuote quote = marketDataProvider.loadQuote(instrument.normalizedSymbol());
                LocalDate quoteDate = quote.asOf().atZone(CHINA_ZONE).toLocalDate();
                boolean latestAvailable = quoteDate.equals(asOf) || quoteDate.equals(latestTradeDate);
                if (!quoteDate.isAfter(asOf) && latestAvailable
                        && quote.price() != null && quote.price().signum() > 0) {
                    List<String> warnings = new ArrayList<>(quote.warnings());
                    if (!quoteDate.equals(asOf)) {
                        warnings.add("latest_quote_date_precedes_requested_as_of=" + quoteDate);
                    }
                    PriceSourceType sourceType = quoteDate.equals(asOf)
                            ? PriceSourceType.REALTIME_QUOTE
                            : PriceSourceType.LATEST_AVAILABLE_QUOTE;
                    return new MarketPriceSnapshot(instrument.normalizedSymbol(), quote.name(), asOf, quoteDate,
                            sourceType, quote.price(),
                            CanonicalUnitNormalizer.lotsToShares(quote.volume()),
                            CanonicalUnitNormalizer.wanYuanToYuan(quote.amount()),
                            CanonicalUnitNormalizer.percentToRatio(quote.turnoverPct()),
                            CanonicalUnitNormalizer.yiYuanToYuan(quote.marketCapYi()),
                            CanonicalUnitNormalizer.yiYuanToYuan(quote.floatMarketCapYi()),
                            quote.peTtm(), quote.pb(), quote.source(), quote.datasetId(), warnings);
                }
            } catch (RuntimeException e) {
                quoteFallbackWarning = "realtime_quote_unavailable_fell_back_to_latest_bar";
            }
        }

        List<String> warnings = new ArrayList<>(dataset.warnings());
        if (quoteFallbackWarning != null) warnings.add(quoteFallbackWarning);
        return new MarketPriceSnapshot(instrument.normalizedSymbol(), instrument.name(), asOf, latestTradeDate,
                PriceSourceType.HISTORICAL_ADJUSTED_CLOSE, latestBar.close(),
                CanonicalUnitNormalizer.lotsToShares(latestBar.volume()), null, null,
                null, null, null, null,
                dataset.source(), dataset.datasetId(), warnings);
    }

    private FinancialSnapshot loadFinancials(
            Instrument instrument, LocalDate asOf, Map<String, String> failures) {
        Map<String, List<CanonicalFinancialStatementPeriod>> result = new LinkedHashMap<>();
        int filtered = 0;
        for (String type : STATEMENT_TYPES) {
            try {
                List<FinancialStatementPeriod> loaded = financialReportProvider.load(
                        instrument.normalizedSymbol(), type, 20);
                List<CanonicalFinancialStatementPeriod> accepted = loaded.stream()
                        .filter(value -> value.initialPublishedDate() != null
                                && !value.initialPublishedDate().isAfter(asOf)
                                && value.latestUpdatedDate() != null
                                && !value.latestUpdatedDate().isAfter(asOf))
                        .sorted(Comparator.comparing(FinancialStatementPeriod::reportPeriod).reversed())
                        .map(financialUnitNormalizer::normalize)
                        .toList();
                filtered += loaded.size() - accepted.size();
                result.put(type, accepted);
            } catch (RuntimeException e) {
                failures.put("financial:" + type, stableFailure(e));
                result.put(type, List.of());
            }
        }
        return new FinancialSnapshot(result, filtered);
    }

    private FilteredList<AnalystReport> loadResearch(
            Instrument instrument, LocalDate asOf, Map<String, String> failures) {
        try {
            List<AnalystReport> loaded = analystResearchProvider.loadCompanyReports(
                    instrument.normalizedSymbol(), 100);
            List<AnalystReport> accepted = loaded.stream()
                    .filter(value -> value.publishedDate() != null && !value.publishedDate().isAfter(asOf))
                    .toList();
            return new FilteredList<>(accepted, loaded.size() - accepted.size());
        } catch (RuntimeException e) {
            failures.put("research", stableFailure(e));
            return new FilteredList<>(List.of(), 0);
        }
    }

    private FilteredList<DividendDistribution> loadDividends(
            Instrument instrument, LocalDate asOf, Map<String, String> failures) {
        try {
            List<DividendDistribution> loaded = dividendProvider.loadHistory(instrument.normalizedSymbol(), 100);
            List<DividendDistribution> accepted = loaded.stream()
                    .filter(value -> value.exDividendDate() != null && !value.exDividendDate().isAfter(asOf))
                    .toList();
            return new FilteredList<>(accepted, loaded.size() - accepted.size());
        } catch (RuntimeException e) {
            failures.put("dividend", stableFailure(e));
            return new FilteredList<>(List.of(), 0);
        }
    }

    private String stableFailure(RuntimeException error) {
        return error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
    }

    private record FilteredList<T>(List<T> values, int filteredFuture) {
        private FilteredList {
            values = List.copyOf(values);
        }
    }
}
