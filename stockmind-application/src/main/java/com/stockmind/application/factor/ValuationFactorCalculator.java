package com.stockmind.application.factor;

import com.stockmind.application.research.AnalystForecastConsensus;
import com.stockmind.application.snapshot.PointInTimeStockSnapshot;
import com.stockmind.domain.factor.*;
import java.math.*;import java.time.LocalDate;import java.util.*;

/** Calculates current, peer, forecast and point-in-time historical valuation factors. */
public final class ValuationFactorCalculator implements FactorCalculator {
    /** {@inheritDoc} */
    public FactorCategory category(){return FactorCategory.VALUATION;}
    /** Uses only values admitted to the supplied snapshot; peer samples exclude the subject stock. */
    public FactorCategoryResult calculate(PointInTimeStockSnapshot s){
        List<FactorValue> f=new ArrayList<>(); List<FactorWarning>w=new ArrayList<>(); List<String>signals=new ArrayList<>();
        var p=s.marketPrice(); BigDecimal pe=positive(p.peTtm()),pb=positive(p.pb()); LocalDate d=p.effectiveTradeDate();
        f.add(pe==null?FactorSupport.value("pe_ttm",p.peTtm(),null,"multiple",FactorDirection.LOWER_IS_BETTER,s,d,List.of(p.source()),"provider_pe_ttm",FactorQuality.INVALID,List.of(FactorWarning.NEGATIVE_EARNINGS),"亏损或非正PE不评分"):
                FactorSupport.value("pe_ttm",pe,pe,"multiple",FactorDirection.LOWER_IS_BETTER,s,d,List.of(p.source()),"provider_pe_ttm",FactorQuality.VALID,List.of(FactorWarning.LOW_PE_REQUIRES_CONTEXT),""));
        f.add(pb==null?FactorSupport.missing("pb","multiple",FactorDirection.LOWER_IS_BETTER,s,"provider_pb","PB缺失或非正"):
                FactorSupport.value("pb",pb,pb,"multiple",FactorDirection.LOWER_IS_BETTER,s,d,List.of(p.source()),"provider_pb",FactorQuality.VALID,List.of(),""));
        BigDecimal ey=pe==null?null:BigDecimal.ONE.divide(pe,FactorSupport.SCALE,RoundingMode.HALF_UP);
        f.add(ey==null?FactorSupport.missing("earnings_yield_pct","percent",FactorDirection.HIGHER_IS_BETTER,s,"100 / pe_ttm","PE无效"):
                FactorSupport.value("earnings_yield_pct",FactorSupport.pct(ey),ey,"percent",FactorDirection.HIGHER_IS_BETTER,s,d,List.of(p.source()),"100 / pe_ttm",FactorQuality.VALID,List.of(),""));
        List<BigDecimal> peerPe=s.industryPeers().marketValues().stream().filter(v->!v.normalizedSymbol().equals(s.instrument().normalizedSymbol())).map(v->positive(v.peTtm())).filter(Objects::nonNull).toList();
        List<BigDecimal> peerPb=s.industryPeers().marketValues().stream().filter(v->!v.normalizedSymbol().equals(s.instrument().normalizedSymbol())).map(v->positive(v.pb())).filter(Objects::nonNull).toList();
        addPeer(f,"peer_pe_premium_pct",pe,peerPe,s,"target_pe_ttm / pure_peer_pe_ttm_median - 1");
        addPeer(f,"peer_pb_premium_pct",pb,peerPb,s,"target_pb / pure_peer_pb_median - 1");
        BigDecimal ttm=s.dividends().stream().filter(x->!x.exDividendDate().isBefore(s.requestedAsOf().minusYears(1))).map(x->x.cashPerTenShares().divide(BigDecimal.TEN,8,RoundingMode.HALF_UP)).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal dy=FactorSupport.div(ttm,p.priceCny());
        f.add(FactorSupport.value("dividend_yield_pct",FactorSupport.pct(dy),dy,"percent",FactorDirection.HIGHER_IS_BETTER,s,d,List.of("eastmoney_dividend",p.source()),"ttm_cash_dividend_per_share / snapshot_price",dy==null?FactorQuality.MISSING:FactorQuality.VALID,List.of(),dy==null?"价格或分红缺失":""));
        AnalystForecastConsensus consensus = new AnalystForecastConsensus();
        int currentFiscalYear = s.requestedAsOf().getYear();
        var currentForecast = consensus.summarize(
                s.analystReports(), s.requestedAsOf(), currentFiscalYear);
        var thirdYearForecast = consensus.summarize(
                s.analystReports(), s.requestedAsOf(), currentFiscalYear + 2);
        BigDecimal med=currentForecast.median(); BigDecimal forward=FactorSupport.div(p.priceCny(),med); FactorQuality fq=currentForecast.sampleCount()<3?FactorQuality.LOW_SAMPLE:FactorQuality.VALID;
        f.add(FactorSupport.value("forward_pe",forward,forward,"multiple",FactorDirection.LOWER_IS_BETTER,s,d,List.of(p.source(),"eastmoney_research"),"snapshot_price / fiscal_year_aligned_institution_eps_median(year="+currentFiscalYear+")",forward==null?FactorQuality.MISSING:fq,currentForecast.sampleCount()<3?List.of(FactorWarning.LOW_FORECAST_SAMPLE):List.of(),currentForecast.sampleCount()<3?"有效机构少于3家":"forecast_year="+currentFiscalYear+",sample_count="+currentForecast.sampleCount()));
        BigDecimal m2=thirdYearForecast.median(),cagr=null;
        if(med!=null&&m2!=null)cagr=BigDecimal.valueOf(Math.pow(m2.divide(med,12,RoundingMode.HALF_UP).doubleValue(),0.5)-1);
        f.add(FactorSupport.value("forecast_eps_cagr_pct",FactorSupport.pct(cagr),cagr,"percent",FactorDirection.HIGHER_IS_BETTER,s,d,List.of("eastmoney_research"),"(fiscal_year_"+(currentFiscalYear+2)+"_eps_median / fiscal_year_"+currentFiscalYear+"_eps_median)^(1/2)-1",cagr==null?FactorQuality.MISSING:fq,Math.min(currentForecast.sampleCount(),thirdYearForecast.sampleCount())<3?List.of(FactorWarning.LOW_FORECAST_SAMPLE):List.of(),"forecast_years="+currentFiscalYear+","+(currentFiscalYear+2)));
        var historical=new HistoricalValuationService();var history=historical.calculate(s);for(int years:new int[]{3,5}){List<BigDecimal>hpe=history.peSince(s.requestedAsOf().minusYears(years)),hpb=history.pbSince(s.requestedAsOf().minusYears(years));BigDecimal pep=historical.percentile(hpe,pe),pbp=historical.percentile(hpb,pb);int minimum=years==3?500:900;f.add(FactorSupport.value("pe_ttm_percentile_"+years+"y",pep,pep==null?null:pep.movePointLeft(2),"percentile",FactorDirection.LOWER_IS_BETTER,s,d,List.of("tencent_adjusted_daily_bars","sina_financial"),"point_in_time_price / reconstructed_ttm_eps; empirical_percentile",historicalQuality(pep,hpe.size(),minimum),List.of(),historicalLimitation(pep,hpe.size(),minimum)));f.add(FactorSupport.value("pb_percentile_"+years+"y",pbp,pbp==null?null:pbp.movePointLeft(2),"percentile",FactorDirection.LOWER_IS_BETTER,s,d,List.of("tencent_adjusted_daily_bars","sina_financial"),"point_in_time_price / latest_published_bvps; empirical_percentile",historicalQuality(pbp,hpb.size(),minimum),List.of(),historicalLimitation(pbp,hpb.size(),minimum)));}
        if(pe!=null&&pe.compareTo(BigDecimal.valueOf(15))<0)signals.add("TTM PE较低但必须结合盈利质量解释"); if(dy!=null&&dy.compareTo(new BigDecimal("0.03"))>=0)signals.add("TTM股息率不低于3%");
        return FactorSupport.result(category(),f,w,signals);
    }
    private void addPeer(List<FactorValue>f,String name,BigDecimal target,List<BigDecimal>peers,PointInTimeStockSnapshot s,String formula){BigDecimal median=FactorSupport.median(peers),premium=median==null?null:FactorSupport.div(target,median);if(premium!=null)premium=premium.subtract(BigDecimal.ONE);FactorQuality q=peers.size()<8?FactorQuality.LOW_SAMPLE:FactorQuality.VALID;f.add(FactorSupport.value(name,FactorSupport.pct(premium),premium,"percent",FactorDirection.LOWER_IS_BETTER,s,s.marketPrice().effectiveTradeDate(),List.of("eastmoney_sector","tencent_quote"),formula,premium==null?FactorQuality.MISSING:q,peers.size()<8?List.of(FactorWarning.INSUFFICIENT_DATA):List.of(),"pure_peer_sample_count="+peers.size()));}
    /** Historical percentiles require both a current comparison value and enough observations. */
    private FactorQuality historicalQuality(BigDecimal percentile,int sampleCount,int minimum){return percentile!=null&&sampleCount>=minimum?FactorQuality.VALID:FactorQuality.MISSING;}
    private String historicalLimitation(BigDecimal percentile,int sampleCount,int minimum){if(percentile==null)return "current_metric_or_historical_distribution_unavailable;sample_count="+sampleCount;if(sampleCount<minimum)return "insufficient_history;sample_count="+sampleCount+",minimum="+minimum;return "sample_count="+sampleCount;}
    private static BigDecimal positive(BigDecimal v){return positiveBool(v)?v:null;} private static boolean positiveBool(BigDecimal v){return v!=null&&v.signum()>0;}
}
