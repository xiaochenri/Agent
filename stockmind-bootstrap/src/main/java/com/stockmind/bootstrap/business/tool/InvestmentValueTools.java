package com.stockmind.bootstrap.business.tool;

import com.agent.javascope.tool.annotation.AgentTool;
import com.stockmind.application.analysis.FinancialTrendPoint;
import com.stockmind.application.analysis.SupplementalEvidenceAnalysisService;
import com.stockmind.application.dividend.DividendDistribution;
import com.stockmind.application.dividend.DividendProvider;
import com.stockmind.application.financial.FinancialReportProvider;
import com.stockmind.application.financial.FinancialStatementPeriod;
import com.stockmind.application.market.MarketDataProvider;
import com.stockmind.application.research.AnalystReport;
import com.stockmind.application.research.AnalystForecastConsensus;
import com.stockmind.application.research.AnalystResearchProvider;
import com.stockmind.application.sector.SectorDataProvider;
import com.stockmind.domain.market.MarketQuote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Deterministic valuation tools backed only by public market, research and filing APIs. */
@Component
public class InvestmentValueTools extends StockToolSupport {
    private static final Logger LOG = LoggerFactory.getLogger(InvestmentValueTools.class);
    private static final String SYMBOL_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$"},"as_of":{"type":"string","format":"date"}},"required":["symbol"],"additionalProperties":false}
            """;
    private static final String REPORT_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$"},"as_of":{"type":"string","format":"date"},"limit":{"type":"integer","minimum":1,"maximum":50}},"required":["symbol"],"additionalProperties":false}
            """;
    private static final String TREND_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","pattern":"^\\\\d{6}$"},"as_of":{"type":"string","format":"date"},"periods":{"type":"integer","minimum":2,"maximum":20}},"required":["symbol"],"additionalProperties":false}
            """;

    private final MarketDataProvider marketDataProvider;
    private final AnalystResearchProvider analystResearchProvider;
    private final DividendProvider dividendProvider;
    private final FinancialReportProvider financialReportProvider;
    private final SupplementalEvidenceAnalysisService evidenceAnalysis = new SupplementalEvidenceAnalysisService();
    private final SectorDataProvider sectorDataProvider;

    public InvestmentValueTools(MarketDataProvider marketDataProvider,
                                AnalystResearchProvider analystResearchProvider,
                                DividendProvider dividendProvider,
                                FinancialReportProvider financialReportProvider,
                                SectorDataProvider sectorDataProvider) {
        this.marketDataProvider = marketDataProvider;
        this.analystResearchProvider = analystResearchProvider;
        this.dividendProvider = dividendProvider;
        this.financialReportProvider = financialReportProvider;
        this.sectorDataProvider = sectorDataProvider;
    }

    @AgentTool(name = "analyst_consensus_forecast", title = "机构一致预期与前向估值",
            namespace = "finance.valuation", category = "forward_valuation",
            tags = {"stock", "eps_forecast", "forward_pe", "readonly"}, inputSchema = REPORT_SCHEMA,
            description = "聚合机构最新年度EPS预测，返回机构数、中位数、分歧区间和前向PE。所有预测均是机构观点，适合预期与Forward估值问题。")
    public String analystConsensusForecast(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("analyst_consensus_forecast", StockToolError.SYMBOL_REQUIRED);
        try {
            LocalDate asOf = asOf(input);
            List<AnalystReport> reports = analystResearchProvider.loadCompanyReports(symbol, 100)
                    .stream().filter(report -> !report.publishedDate().isAfter(asOf)).toList();
            MarketQuote quote = marketDataProvider.loadQuote(symbol);
            AnalystForecastConsensus consensus = new AnalystForecastConsensus();
            List<Map<String, Object>> years = new ArrayList<>();
            for (int year = asOf.getYear(); year <= asOf.getYear() + 2; year++) {
                var summary = consensus.summarize(reports, asOf, year);
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("sample_count", summary.sampleCount());
                stats.put("median_eps", summary.median());
                stats.put("mean_eps", summary.mean());
                stats.put("min_eps", summary.minimum());
                stats.put("max_eps", summary.maximum());
                stats.put("year", year);
                stats.put("eps_basis", "FISCAL_YEAR_ALIGNED_LATEST_PER_INSTITUTION");
                stats.put("forward_pe", summary.median() == null ? null
                        : divide(quote.price(), summary.median()));
                years.add(stats);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("as_of", asOf.toString());
            data.put("price", quote.price());
            data.put("pe_ttm", quote.peTtm());
            data.put("source", List.of("eastmoney_report_api", quote.source()));
            data.put("institution_count", reports.stream().map(AnalystReport::institution)
                    .filter(value -> value != null && !value.isBlank()).distinct().count());
            int maximumForecastSamples = years.stream()
                    .mapToInt(value -> ((Number) value.get("sample_count")).intValue()).max().orElse(0);
            data.put("forecast_quality", maximumForecastSamples < 3 ? "LOW_SAMPLE" : "VALID");
            data.put("yearly_forecasts", years);
            data.put("analysis_capability", capability(
                    "FORWARD_VALUATION", "AVAILABLE",
                    List.of("valuation_safety_margin", "growth_sustainability", "expectation_gap")));
            data.put("limitations", List.of("机构预测属于观点而非已实现业绩。",
                    "每家机构仅保留截至as_of的最新一份研报，前向PE使用EPS中位数。"));
            return success("analyst_consensus_forecast", data,
                    "[\"研报来自东财公开接口\",\"同一机构已去重\",\"前向PE使用年度预测EPS中位数\"]");
        } catch (IllegalArgumentException e) {
            return fail("analyst_consensus_forecast", StockToolError.INVALID_TIME_WINDOW);
        } catch (Exception e) {
            log("analyst_consensus_forecast", e);
            return fail("analyst_consensus_forecast", StockToolError.RESEARCH_SERVICE_UNAVAILABLE);
        }
    }

    @AgentTool(name = "research_report_search", title = "个股机构研报检索",
            namespace = "finance.research", category = "research_report",
            tags = {"stock", "research", "rating", "readonly"}, inputSchema = REPORT_SCHEMA,
            description = "检索个股机构研报，返回机构、评级、预测和原文链接。用于查看观点分布与依据，不把评级或预测当作已发生事实。")
    public String researchReportSearch(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("research_report_search", StockToolError.SYMBOL_REQUIRED);
        try {
            LocalDate asOf = asOf(input);
            int limit = Math.min(50, positiveInt(input.get("limit"), 20));
            List<Map<String, Object>> items = filteredReports(symbol, asOf, limit).stream().map(this::reportMap).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("as_of", asOf.toString());
            data.put("source", "eastmoney_report_api");
            data.put("returned_count", items.size());
            data.put("items", items);
            data.put("analysis_capability", capability(
                    "RESEARCH_CONTEXT", "DISCOVERY_ONLY",
                    List.of("growth_sustainability", "expectation_gap")));
            return success("research_report_search", data,
                    "[\"研报元数据来自东财公开接口\",\"包含PDF原文链接\",\"预测与评级标记为机构观点\"]");
        } catch (Exception e) {
            log("research_report_search", e);
            return fail("research_report_search", StockToolError.RESEARCH_SERVICE_UNAVAILABLE);
        }
    }

    @AgentTool(name = "peer_valuation_comparison", title = "同行前10成分股估值比较",
            namespace = "finance.valuation", category = "peer_valuation",
            tags = {"stock", "peer", "pe_ttm", "pb", "readonly"}, inputSchema = SYMBOL_SCHEMA,
            description = "比较所属行业板块前10只大市值成分股的TTM PE/PB。结果是宽泛行业参照，不等同于经过商业模式筛选的严格可比公司组。")
    public String peerValuationComparison(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("peer_valuation_comparison", StockToolError.SYMBOL_REQUIRED);
        try {
            var set = sectorDataProvider.loadTopIndustryConstituents(symbol, 10);
            List<Map<String, Object>> peers = new ArrayList<>();
            List<BigDecimal> peValues = new ArrayList<>(), pbValues = new ArrayList<>();
            List<String> unavailable = new ArrayList<>();
            MarketQuote subject = marketDataProvider.loadQuote(symbol);
            for (var constituent : set.constituents().stream().limit(10).toList()) {
                try {
                    MarketQuote quote = constituent.normalizedSymbol().endsWith(subject.instrumentId())
                            ? subject : marketDataProvider.loadQuote(constituent.normalizedSymbol());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("symbol", constituent.normalizedSymbol());
                    item.put("name", constituent.name());
                    item.put("market_cap_yi", quote.marketCapYi());
                    item.put("pe_ttm", positive(quote.peTtm()));
                    item.put("pb", positive(quote.pb()));
                    peers.add(item);
                    if (positive(quote.peTtm()) != null) peValues.add(quote.peTtm());
                    if (positive(quote.pb()) != null) pbValues.add(quote.pb());
                } catch (Exception e) {
                    unavailable.add(constituent.normalizedSymbol());
                }
            }
            BigDecimal medianPe = median(peValues), medianPb = median(pbValues);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("sector_code", set.sectorCode());
            data.put("sector_name", set.sectorName());
            data.put("selection_rule", "行业板块成分股按总市值降序，仅取前10只");
            data.put("requested_constituent_count", 10);
            data.put("returned_constituent_count", peers.size());
            data.put("valid_pe_sample_count", peValues.size());
            data.put("valid_pb_sample_count", pbValues.size());
            data.put("peer_median_pe_ttm", medianPe);
            data.put("peer_median_pb", medianPb);
            data.put("subject_pe_ttm", positive(subject.peTtm()));
            data.put("subject_pb", positive(subject.pb()));
            data.put("subject_pe_percentile", percentile(peValues, positive(subject.peTtm())));
            data.put("valuation_vs_peer_median", compare(subject.peTtm(), medianPe));
            data.put("source", List.of("eastmoney_sector_api", subject.source()));
            data.put("unavailable_symbols", unavailable);
            data.put("constituents", peers);
            data.put("analysis_capability", capability(
                    "PEER_VALUATION_CONTEXT", "AVAILABLE", List.of("valuation_safety_margin")));
            return success("peer_valuation_comparison", data,
                    "[\"同行范围限定为按总市值排序的前10只行业成分股\",\"PE统一使用腾讯TTM口径\",\"亏损及缺失PE不进入中位数\"]");
        } catch (Exception e) {
            log("peer_valuation_comparison", e);
            return fail("peer_valuation_comparison", StockToolError.VALUATION_COMPARISON_UNAVAILABLE);
        }
    }

    @AgentTool(name = "dividend_analysis", title = "分红与滚动股息率分析",
            namespace = "finance.valuation", category = "dividend_analysis",
            tags = {"stock", "dividend", "yield", "readonly"}, inputSchema = SYMBOL_SCHEMA,
            description = "读取东财已披露分红历史，将每10股派息标准化为每股派息，并按实时股价计算过去12个月滚动股息率。")
    public String dividendAnalysis(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("dividend_analysis", StockToolError.SYMBOL_REQUIRED);
        try {
            LocalDate asOf = asOf(input);
            MarketQuote quote = marketDataProvider.loadQuote(symbol);
            List<DividendDistribution> history = dividendProvider.loadHistory(symbol, 30);
            BigDecimal ttmPerShare = history.stream()
                    .filter(d -> !d.exDividendDate().isAfter(asOf) && d.exDividendDate().isAfter(asOf.minusYears(1)))
                    .map(d -> d.cashPerTenShares().divide(BigDecimal.TEN, 8, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Map<String, Object>> records = history.stream().limit(20).map(d -> Map.<String, Object>of(
                    "ex_dividend_date", d.exDividendDate().toString(),
                    "cash_per_ten_shares", d.cashPerTenShares(),
                    "cash_per_share", d.cashPerTenShares().divide(BigDecimal.TEN, 6, RoundingMode.HALF_UP),
                    "progress", d.progress())).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("as_of", asOf.toString());
            data.put("price", quote.price());
            data.put("ttm_cash_dividend_per_share", ttmPerShare);
            data.put("ttm_dividend_yield_pct", quote.price().signum() <= 0 ? null
                    : ttmPerShare.multiply(BigDecimal.valueOf(100)).divide(quote.price(), 6, RoundingMode.HALF_UP));
            data.put("source", List.of("eastmoney_datacenter_api", quote.source()));
            data.put("records", records);
            data.put("limitations", List.of("股息率按除权日落在过去12个月的已实施现金分红计算。",
                    "未扣除个人所得税，也不代表未来分红承诺。"));
            return success("dividend_analysis", data,
                    "[\"东财分红原始口径为每10股\",\"已标准化为每股\",\"滚动股息率使用实时股价确定性计算\"]");
        } catch (Exception e) {
            log("dividend_analysis", e);
            return fail("dividend_analysis", StockToolError.DIVIDEND_SERVICE_UNAVAILABLE);
        }
    }

    @AgentTool(name = "financial_trend_analysis", title = "多期财务趋势分析",
            namespace = "finance.fundamental", category = "financial_trend",
            tags = {"stock", "financial", "trend", "cash_flow", "readonly"}, inputSchema = TREND_SCHEMA,
            description = "比较最近多期营收、净利润、现金流、利润率、负债率和现金转化，并输出增长与盈利质量业务信号。")
    public String financialTrendAnalysis(Map<String, Object> input, String raw) {
        String symbol = symbol(input, raw);
        if (symbol.isBlank()) return fail("financial_trend_analysis", StockToolError.SYMBOL_REQUIRED);
        try {
            LocalDate asOf = asOf(input);
            int periods = Math.min(20, Math.max(2, positiveInt(input.get("periods"), 8)));
            List<FinancialStatementPeriod> income = available(financialReportProvider.load(symbol, "lrb", periods), asOf);
            List<FinancialStatementPeriod> balance = available(financialReportProvider.load(symbol, "fzb", periods), asOf);
            List<FinancialStatementPeriod> cash = available(financialReportProvider.load(symbol, "llb", periods), asOf);
            List<Map<String, Object>> rows = new ArrayList<>();
            List<FinancialTrendPoint> trendPoints = new ArrayList<>();
            for (FinancialStatementPeriod statement : income) {
                FinancialStatementPeriod b = samePeriod(balance, statement.reportPeriod());
                FinancialStatementPeriod c = samePeriod(cash, statement.reportPeriod());
                BigDecimal revenue = metric(statement, "营业总收入", "营业收入");
                BigDecimal profit = metric(statement, "归属于母公司所有者的净利润", "归属于母公司股东的净利润",
                        "归属于上市公司股东的净利润", "净利润");
                BigDecimal operatingCash = metric(c, "经营活动产生的现金流量净额");
                BigDecimal assets = metric(b, "资产总计", "资产合计");
                BigDecimal liabilities = metric(b, "负债合计");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("report_period", statement.reportPeriod().toString());
                row.put("published_date", statement.publishedDate().toString());
                row.put("revenue", revenue);
                row.put("revenue_yoy_pct", yoy(statement, "营业总收入", "营业收入"));
                row.put("net_profit", profit);
                row.put("net_profit_yoy_pct", yoy(statement, "归属于母公司所有者的净利润",
                        "归属于母公司股东的净利润", "归属于上市公司股东的净利润", "净利润"));
                row.put("net_margin_pct", ratio(profit, revenue));
                row.put("operating_cash_flow", operatingCash);
                row.put("cash_to_net_profit_ratio", plainRatio(operatingCash, profit));
                row.put("total_assets", assets);
                row.put("total_liabilities", liabilities);
                row.put("liability_to_asset_pct", ratio(liabilities, assets));
                rows.add(row);
                trendPoints.add(new FinancialTrendPoint(
                        statement.reportPeriod(),
                        yoy(statement, "营业总收入", "营业收入"),
                        yoy(statement, "归属于母公司所有者的净利润",
                                "归属于母公司股东的净利润", "归属于上市公司股东的净利润", "净利润"),
                        plainRatio(operatingCash, profit)));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", symbol);
            data.put("as_of", asOf.toString());
            data.put("source", "sina_financial_api");
            data.put("returned_periods", rows.size());
            data.put("periods", rows);
            data.put("business_signals", StockBusinessSignalMapper.maps(
                    evidenceAnalysis.financialTrendSignals(trendPoints, asOf)));
            data.put("analysis_capability", capability(
                    "FINANCIAL_TREND", "AVAILABLE",
                    List.of("growth_sustainability", "earnings_quality_durability")));
            data.put("limitations", List.of("季度与年度累计口径不能直接环比，优先阅读同比字段。",
                    "只使用截至as_of已披露的报告。"));
            return success("financial_trend_analysis", data,
                    "[\"三表来自新浪结构化接口\",\"按披露日期过滤\",\"利润率和现金转化由确定性公式计算\"]");
        } catch (Exception e) {
            log("financial_trend_analysis", e);
            return fail("financial_trend_analysis", StockToolError.FINANCIAL_REPORT_SERVICE_UNAVAILABLE);
        }
    }

    private List<AnalystReport> filteredReports(String symbol, LocalDate asOf, int limit) {
        Map<String, AnalystReport> latestByInstitution = analystResearchProvider.loadCompanyReports(symbol, limit).stream()
                .filter(r -> !r.publishedDate().isAfter(asOf) && !r.publishedDate().isBefore(asOf.minusYears(1)))
                .filter(r -> r.institution() != null && !r.institution().isBlank())
                .sorted(Comparator.comparing(AnalystReport::publishedDate).reversed())
                .collect(Collectors.toMap(AnalystReport::institution, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
        return List.copyOf(latestByInstitution.values());
    }

    /** 补充工具声明自己覆盖的证据能力，供运行时更新分析议题而非猜测工具用途。 */
    private Map<String, Object> capability(
            String capability, String resolutionMode, List<String> agendaIds) {
        return Map.of(
                "capability", capability,
                "resolution_mode", resolutionMode,
                "updates_agenda_ids", agendaIds);
    }

    private Map<String, Object> reportMap(AnalystReport report) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", report.id());
        item.put("title", report.title());
        item.put("institution", report.institution());
        item.put("published_at", report.publishedDate().toString());
        item.put("rating", report.rating());
        item.put("forecast_year", report.publishedDate().getYear());
        item.put("current_year_eps", report.currentYearEps());
        item.put("next_year_eps", report.nextYearEps());
        item.put("next_two_year_eps", report.nextTwoYearEps());
        item.put("pdf_url", report.pdfUrl());
        return item;
    }

    private List<FinancialStatementPeriod> available(List<FinancialStatementPeriod> reports, LocalDate asOf) {
        return reports.stream().filter(r -> !r.publishedDate().isAfter(asOf))
                .sorted(Comparator.comparing(FinancialStatementPeriod::reportPeriod).reversed()).toList();
    }

    private FinancialStatementPeriod samePeriod(List<FinancialStatementPeriod> reports, LocalDate period) {
        return reports.stream().filter(r -> r.reportPeriod().equals(period)).findFirst().orElse(null);
    }

    private BigDecimal metric(FinancialStatementPeriod report, String... labels) {
        if (report == null) return null;
        for (String label : labels) {
            String value = report.values().get(label);
            try { if (value != null && !value.isBlank()) return new BigDecimal(value.replace(",", "")); }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private BigDecimal yoy(FinancialStatementPeriod report, String... labels) {
        if (report == null) return null;
        for (String label : labels) {
            String value = report.yearOverYearValues().get(label);
            try { if (value != null && !value.isBlank()) return new BigDecimal(value).multiply(BigDecimal.valueOf(100)); }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() == 0 ? null
                : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal plainRatio(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() == 0 ? null
                : numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private BigDecimal percentile(List<BigDecimal> values, BigDecimal subject) {
        if (subject == null || values.isEmpty()) return null;
        long atOrBelow = values.stream().filter(value -> value.compareTo(subject) <= 0).count();
        return BigDecimal.valueOf(atOrBelow * 100D / values.size()).setScale(2, RoundingMode.HALF_UP);
    }

    private String compare(BigDecimal subject, BigDecimal median) {
        if (positive(subject) == null || median == null) return "INSUFFICIENT_DATA";
        int comparison = subject.compareTo(median);
        return comparison > 0 ? "ABOVE_PEER_MEDIAN" : comparison < 0 ? "BELOW_PEER_MEDIAN" : "AT_PEER_MEDIAN";
    }

    private LocalDate asOf(Map<String, Object> input) {
        String value = asString(input.get("as_of"));
        return value.isBlank() ? LocalDate.now() : LocalDate.parse(value);
    }

    private void log(String tool, Exception error) {
        LOG.warn("Investment data tool failed, tool={}, exceptionType={}", tool, error.getClass().getName());
        LOG.debug("Investment data tool failure details, tool={}", tool, error);
    }
}
