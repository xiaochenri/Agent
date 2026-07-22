package com.stockmind.application.factor;

import com.stockmind.application.financial.CanonicalFinancialStatementPeriod;
import com.stockmind.application.financial.CanonicalFinancialValue;
import com.stockmind.application.snapshot.PointInTimeStockSnapshot;
import com.stockmind.domain.factor.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

final class FactorSupport {
    static final int SCALE = 8;
    private FactorSupport() {}
    static BigDecimal div(BigDecimal a, BigDecimal b) { return a == null || b == null || b.signum() == 0 ? null : a.divide(b, SCALE, RoundingMode.HALF_UP); }
    static BigDecimal pct(BigDecimal ratio) { return ratio == null ? null : ratio.movePointRight(2); }
    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> v = values.stream().filter(Objects::nonNull).sorted().toList();
        if (v.isEmpty()) return null; int n=v.size(); return n%2==1?v.get(n/2):v.get(n/2-1).add(v.get(n/2)).divide(BigDecimal.TWO,SCALE,RoundingMode.HALF_UP);
    }
    static BigDecimal metric(CanonicalFinancialStatementPeriod p, String... names) {
        if (p == null) return null;
        for (String name:names) { CanonicalFinancialValue v=p.values().get(name); if(v!=null)return v.value(); }
        return null;
    }
    static BigDecimal yoy(CanonicalFinancialStatementPeriod p, String... names) {
        if (p == null) return null;
        for(String name:names){CanonicalFinancialValue v=p.yearOverYearValues().get(name);if(v!=null)return v.value();} return null;
    }
    static CanonicalFinancialStatementPeriod latest(PointInTimeStockSnapshot s,String type) {
        var list=s.financials().statements().getOrDefault(type,List.of()); return list.isEmpty()?null:list.getFirst();
    }
    static FactorValue value(String name,BigDecimal raw,BigDecimal norm,String unit,FactorDirection direction,
                             PointInTimeStockSnapshot s,LocalDate period,List<String> sources,String formula,
                             FactorQuality quality,List<FactorWarning>warnings,String limitation){
        return new FactorValue(name,raw,norm,unit,direction,s.requestedAsOf(),period,sources,formula,quality,warnings,limitation);
    }
    static FactorValue missing(String name,String unit,FactorDirection direction,PointInTimeStockSnapshot s,String formula,String limitation){
        return value(name,null,null,unit,direction,s,null,List.of(),formula,FactorQuality.MISSING,List.of(FactorWarning.INSUFFICIENT_DATA),limitation);
    }
    static FactorCategoryResult result(FactorCategory c,List<FactorValue> factors,List<FactorWarning>warnings,List<String>signals){return new FactorCategoryResult(c,factors,warnings,signals);}
}
