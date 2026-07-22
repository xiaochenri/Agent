package com.stockmind.application.snapshot;

import com.stockmind.application.dataset.ExternalDatasetDefinition;
import com.stockmind.application.dataset.ExternalDatasetRegistry;
import com.stockmind.application.dataset.DatasetAdmissionEvidence;
import com.stockmind.application.dataset.ExternalDatasetAdmissionService;
import com.stockmind.application.dividend.DividendDistribution;
import com.stockmind.application.financial.FinancialStatementPeriod;
import com.stockmind.application.financial.FinancialValueUnit;
import com.stockmind.application.financial.StatementScope;
import com.stockmind.application.instrument.AmbiguousInstrumentException;
import com.stockmind.application.instrument.InstrumentResolver;
import com.stockmind.application.market.HistoricalBarsQuery;
import com.stockmind.application.market.MarketDataProvider;
import com.stockmind.application.research.AnalystReport;
import com.stockmind.domain.instrument.InstrumentType;
import com.stockmind.domain.market.AdjustmentMode;
import com.stockmind.domain.market.BarDataset;
import com.stockmind.domain.market.BarInterval;
import com.stockmind.domain.market.MarketBar;
import com.stockmind.domain.market.MarketQuote;
import com.stockmind.domain.market.PriceSourceType;
import com.stockmind.domain.market.ValuationMetricBasis;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Fixed-input acceptance checks for the stage-0 point-in-time correctness gate. */
public final class PointInTimeStockSnapshotAcceptanceTest {
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    public static void main(String[] args) {
        verifiesInstrumentResolution();
        verifiesHistoricalSnapshotDoesNotLeakFutureData();
        verifiesCurrentSnapshotLoadsPeersOnceAndNormalizesMarketUnits();
        verifiesCurrentRequestUsesLatestTradingDayQuoteValuation();
        verifiesUnitsAndValuationBasis();
        verifiesDatasetAdmissionRules();
    }

    private static void verifiesInstrumentResolution() {
        InstrumentResolver resolver = new InstrumentResolver();
        require("SZ000001".equals(resolver.resolve("000001", InstrumentType.STOCK).normalizedSymbol()),
                "股票代码补全错误");
        require(resolver.resolve("SZ000001").instrumentType() == InstrumentType.STOCK,
                "带市场股票代码类型解析错误");
        require(resolver.resolve("SH000001").instrumentType() == InstrumentType.INDEX,
                "上证指数类型解析错误");
        require("上证指数".equals(resolver.resolve("000001.SH", InstrumentType.INDEX).name()),
                "后缀格式指数解析错误");
        try {
            resolver.resolve("000001");
            throw new AssertionError("裸代码未声明资产类型时必须报歧义");
        } catch (AmbiguousInstrumentException expected) {
            // expected
        }
    }

    private static void verifiesHistoricalSnapshotDoesNotLeakFutureData() {
        LocalDate requested = LocalDate.of(2026, 7, 17);
        Clock clock = Clock.fixed(Instant.parse("2026-07-20T04:00:00Z"), CHINA);
        MarketDataProvider market = new MarketDataProvider() {
            @Override
            public MarketQuote loadQuote(String instrumentId) {
                throw new AssertionError("历史as_of禁止读取实时行情");
            }

            @Override
            public BarDataset loadBars(HistoricalBarsQuery query) {
                return new BarDataset("bars-1", List.of(
                        bar("2026-07-16T07:00:00Z", "1258.99"),
                        bar("2026-07-17T07:00:00Z", "1253.00"),
                        bar("2026-07-20T07:00:00Z", "1327.50")),
                        "fixed-bars", Instant.parse("2026-07-20T08:00:00Z"), List.of());
            }
        };

        PointInTimeStockSnapshotService service = new PointInTimeStockSnapshotService(
                new InstrumentResolver(), market,
                (symbol, type, periods) -> List.of(
                        new FinancialStatementPeriod(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30),
                                type, Map.of("营业收入", "50000000000", "基本每股收益", "12.34"),
                                Map.of("营业收入", "0.085")),
                        new FinancialStatementPeriod(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 8, 20),
                                type, Map.of("营业收入", "2"), Map.of())),
                (symbol, limit) -> List.of(
                        report("old", LocalDate.of(2026, 7, 1)),
                        report("future", LocalDate.of(2026, 7, 19))),
                (symbol, limit) -> List.of(
                        new DividendDistribution(LocalDate.of(2026, 6, 30), BigDecimal.TEN, "实施"),
                        new DividendDistribution(LocalDate.of(2026, 8, 1), BigDecimal.TEN, "预案")),
                new com.stockmind.application.sector.SectorDataProvider() {
                    @Override public List<com.stockmind.application.sector.SectorSnapshot> loadIndustrySectors(String symbol) {
                        throw new AssertionError("历史as_of禁止读取当前行业数据");
                    }
                    @Override public com.stockmind.application.sector.SectorConstituentSet loadTopIndustryConstituents(
                            String symbol, int limit) {
                        throw new AssertionError("历史as_of禁止读取当前同行数据");
                    }
                },
                clock);

        PointInTimeStockSnapshot snapshot = service.load("SH600519", requested);
        require(snapshot.marketPrice().priceSourceType() == PriceSourceType.HISTORICAL_ADJUSTED_CLOSE,
                "历史价格来源类型错误");
        require(snapshot.marketPrice().effectiveTradeDate().equals(requested), "有效交易日错误");
        require(snapshot.marketPrice().priceCny().compareTo(new BigDecimal("1253.00")) == 0,
                "历史快照错误使用了未来价格");
        require(snapshot.marketHistory().bars().size() == 2, "未来K线未从请求级历史序列中过滤");
        require(!snapshot.industryPeers().usableForRequestedAsOf(), "当前同行数据不得进入历史快照");
        require(snapshot.financials().filteredFutureRecords() == 3, "未来财报过滤计数错误");
        require(snapshot.financials().statements().values().stream().allMatch(values -> values.size() == 1),
                "未来财报未被过滤");
        require(snapshot.filteredFutureResearchRecords() == 1 && snapshot.analystReports().size() == 1,
                "未来研报未被过滤");
        require(snapshot.filteredFutureDividendRecords() == 1 && snapshot.dividends().size() == 1,
                "未来分红记录未被过滤");
        require(snapshot.financials().statements().get("lrb").getFirst().statementScope()
                        == StatementScope.CUMULATIVE_QUARTER,
                "财报范围推断错误");
        var income = snapshot.financials().statements().get("lrb").getFirst();
        require(income.values().get("营业收入").unit() == FinancialValueUnit.CNY,
                "财务金额单位契约错误");
        require(income.values().get("基本每股收益").unit() == FinancialValueUnit.CNY_PER_SHARE,
                "每股值单位契约错误");
        require(income.yearOverYearValues().get("营业收入").value()
                        .compareTo(new BigDecimal("0.085")) == 0,
                "财务同比小数比例不应被重复缩放");
    }

    private static void verifiesUnitsAndValuationBasis() {
        require(CanonicalUnitNormalizer.lotsToShares(BigDecimal.valueOf(12))
                        .compareTo(BigDecimal.valueOf(1200)) == 0,
                "手到股转换错误");
        require(CanonicalUnitNormalizer.yiYuanToYuan(BigDecimal.valueOf(2.5))
                        .compareTo(new BigDecimal("250000000.0")) == 0,
                "亿元到元转换错误");
        require(CanonicalUnitNormalizer.wanYuanToYuan(BigDecimal.valueOf(3.5))
                        .compareTo(new BigDecimal("35000.0")) == 0,
                "万元到元转换错误");
        require(!ValuationMetricBasis.PE_TTM.isComparableWith(ValuationMetricBasis.PE_FORWARD),
                "Forward PE不得与TTM PE直接比较");
        require(ValuationMetricBasis.PE_TTM.isComparableWith(ValuationMetricBasis.PE_TTM),
                "同口径PE应可比较");
    }

    private static void verifiesCurrentRequestUsesLatestTradingDayQuoteValuation() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), CHINA);
        MarketDataProvider market = new MarketDataProvider() {
            @Override public MarketQuote loadQuote(String instrumentId) {
                return new MarketQuote("SH603259", "quote-latest", "fixed-quote",
                        Instant.parse("2026-07-20T07:00:00Z"), "药明康德",
                        new BigDecimal("124.22"), new BigDecimal("120"), new BigDecimal("121"),
                        new BigDecimal("125"), new BigDecimal("120"), new BigDecimal("4.22"),
                        new BigDecimal("3.52"), BigDecimal.TEN, BigDecimal.TEN,
                        BigDecimal.ONE, new BigDecimal("18.41"), new BigDecimal("4.66"),
                        new BigDecimal("3072.31"), new BigDecimal("2500"),
                        new BigDecimal("132"), new BigDecimal("108"), BigDecimal.ONE, List.of());
            }

            @Override public BarDataset loadBars(HistoricalBarsQuery query) {
                return new BarDataset("bars-latest", List.of(bar("2026-07-20T07:00:00Z", "124.22")),
                        "fixed-bars", Instant.parse("2026-07-20T08:00:00Z"), List.of());
            }
        };
        var sectors = new com.stockmind.application.sector.SectorDataProvider() {
            @Override public List<com.stockmind.application.sector.SectorSnapshot> loadIndustrySectors(String symbol) {
                return List.of();
            }
            @Override public com.stockmind.application.sector.SectorConstituentSet loadTopIndustryConstituents(
                    String symbol, int limit) {
                return new com.stockmind.application.sector.SectorConstituentSet("BK0727", "医疗服务", List.of());
            }
        };
        var service = new PointInTimeStockSnapshotService(
                new InstrumentResolver(), market, (symbol, type, periods) -> List.of(),
                (symbol, limit) -> List.of(), (symbol, limit) -> List.of(), sectors, clock);
        PointInTimeStockSnapshot snapshot = service.load("SH603259", LocalDate.of(2026, 7, 21));
        require(snapshot.marketPrice().priceSourceType() == PriceSourceType.LATEST_AVAILABLE_QUOTE,
                "盘前或非交易日应使用最近交易日行情口径");
        require(snapshot.marketPrice().effectiveTradeDate().equals(LocalDate.of(2026, 7, 20)),
                "最近行情的有效交易日错误");
        require(snapshot.marketPrice().peTtm().compareTo(new BigDecimal("18.41")) == 0,
                "最近交易日PE不应在历史收盘价降级时丢失");
    }

    private static void verifiesCurrentSnapshotLoadsPeersOnceAndNormalizesMarketUnits() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-20T04:00:00Z"), CHINA);
        MarketDataProvider market = new MarketDataProvider() {
            @Override public MarketQuote loadQuote(String instrumentId) {
                return new MarketQuote("SH600519", "quote-1", "fixed-quote",
                        Instant.parse("2026-07-20T03:30:00Z"), "贵州茅台",
                        new BigDecimal("1300"), new BigDecimal("1290"), new BigDecimal("1295"),
                        new BigDecimal("1310"), new BigDecimal("1280"), BigDecimal.TEN,
                        new BigDecimal("0.775"), new BigDecimal("12"), new BigDecimal("3.5"),
                        new BigDecimal("2.5"), new BigDecimal("20"), new BigDecimal("6"),
                        new BigDecimal("2.5"), new BigDecimal("2.0"), new BigDecimal("1400"),
                        new BigDecimal("1150"), BigDecimal.ONE, List.of());
            }

            @Override public BarDataset loadBars(HistoricalBarsQuery query) {
                return new BarDataset("bars-current", List.of(bar("2026-07-17T07:00:00Z", "1290")),
                        "fixed-bars", Instant.parse("2026-07-20T04:00:00Z"), List.of());
            }
        };
        java.util.concurrent.atomic.AtomicInteger peerCalls = new java.util.concurrent.atomic.AtomicInteger();
        var sectors = new com.stockmind.application.sector.SectorDataProvider() {
            @Override public List<com.stockmind.application.sector.SectorSnapshot> loadIndustrySectors(String symbol) {
                throw new AssertionError("快照应直接加载一次同行集合");
            }

            @Override public com.stockmind.application.sector.SectorConstituentSet loadTopIndustryConstituents(
                    String symbol, int limit) {
                peerCalls.incrementAndGet();
                return new com.stockmind.application.sector.SectorConstituentSet("BK0477", "酿酒行业",
                        List.of(new com.stockmind.application.sector.SectorConstituent(
                                "SH600519", "贵州茅台", new BigDecimal("250000000"))));
            }
        };
        PointInTimeStockSnapshotService service = new PointInTimeStockSnapshotService(
                new InstrumentResolver(), market, (symbol, type, periods) -> List.of(),
                (symbol, limit) -> List.of(), (symbol, limit) -> List.of(), sectors, clock);
        PointInTimeStockSnapshot snapshot = service.load("SH600519", LocalDate.of(2026, 7, 20));
        require(snapshot.industryPeers().usableForRequestedAsOf() && peerCalls.get() == 1,
                "当前同行集合未按请求加载一次");
        require(snapshot.marketPrice().volumeShares().compareTo(new BigDecimal("1200")) == 0,
                "实时成交量未从手转换为股");
        require(snapshot.marketPrice().amountCny().compareTo(new BigDecimal("35000.0")) == 0,
                "实时成交额未从万元转换为元");
        require(snapshot.marketPrice().turnoverRatio().compareTo(new BigDecimal("0.025")) == 0,
                "换手率未从百分比转换为内部小数");
        require(snapshot.marketPrice().marketCapCny().compareTo(new BigDecimal("250000000.0")) == 0,
                "市值未从亿元转换为元");
    }

    private static void verifiesDatasetAdmissionRules() {
        ExternalDatasetRegistry registry = ExternalDatasetRegistry.stockFactorV1();
        var currentOnly = registry.find("tencent", "realtime_quote").orElseThrow();
        require(currentOnly.mayAffectScore(new ExternalDatasetDefinition.LocalDateContext(true)),
                "已准入实时数据应可用于当前时点");
        require(!currentOnly.mayAffectScore(new ExternalDatasetDefinition.LocalDateContext(false)),
                "CURRENT_ONLY数据不得用于历史评分");
        require(!registry.find("eastmoney", "stock_profile").orElseThrow()
                        .mayAffectScore(new ExternalDatasetDefinition.LocalDateContext(true)),
                "候选数据集未完成准入前不得参与评分");
        var evidence = new DatasetAdmissionEvidence(3, true, true, true,
                true, true, true, true);
        require(new ExternalDatasetAdmissionService().check(currentOnly, evidence).admitted(),
                "满足全部证据的端点应通过准入检查");
        var incomplete = new DatasetAdmissionEvidence(2, true, true, true,
                true, false, true, true);
        var rejected = new ExternalDatasetAdmissionService().check(currentOnly, incomplete);
        require(!rejected.admitted() && rejected.failedChecks().contains("LIVE_TEST_THREE_SYMBOLS")
                        && rejected.failedChecks().contains("DEGRADATION_POLICY"),
                "准入检查未返回确定性的失败原因");
    }

    private static AnalystReport report(String id, LocalDate published) {
        return new AnalystReport(id, "600519", id, "机构", published, "买入",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "");
    }

    private static MarketBar bar(String closeTime, String close) {
        Instant closeAt = Instant.parse(closeTime);
        BigDecimal value = new BigDecimal(close);
        return new MarketBar("SH600519", BarInterval.DAY_1, closeAt.minusSeconds(19_800), closeAt,
                value, value, value, value, BigDecimal.TEN, BigDecimal.ZERO, AdjustmentMode.FORWARD);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
