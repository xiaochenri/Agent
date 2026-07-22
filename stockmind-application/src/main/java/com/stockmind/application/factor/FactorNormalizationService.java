package com.stockmind.application.factor;
import com.stockmind.domain.factor.*;import java.math.*;import java.util.*;
public final class FactorNormalizationService{
 /**
  * Normalizes usable values to a 0-100 category score and reports factor coverage.
  * Missing and low-sample values are excluded through {@link FactorValue#usable()}.
  */
 public FactorScore score(FactorCategoryResult r){List<FactorValue>usable=r.factors().stream().filter(FactorValue::usable).toList();BigDecimal coverage=BigDecimal.valueOf(usable.size()*100.0/Math.max(1,r.factors().size())).setScale(2,RoundingMode.HALF_UP);if(usable.isEmpty())return new FactorScore(r.category(),null,coverage,FactorQuality.MISSING,List.of());BigDecimal avg=usable.stream().map(this::score).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(usable.size()),2,RoundingMode.HALF_UP);return new FactorScore(r.category(),avg,coverage,coverage.compareTo(BigDecimal.valueOf(80))>=0?FactorQuality.VALID:FactorQuality.PARTIAL,usable.stream().map(FactorValue::name).toList());}
 private BigDecimal score(FactorValue f){double v=f.normalizedValue().doubleValue(),s;String n=f.name();if(n.contains("pe_ttm")||n.equals("forward_pe"))s=100-(v-5)/40*100;else if(n.equals("pb"))s=100-(v-.5)/9.5*100;else if(n.contains("premium"))s=50-v*100;else if(n.contains("yield")||n.contains("margin")||n.contains("growth")||n.contains("revision")||n.contains("return_"))s=50+v*150;else if(n.contains("cash_to"))s=v/1.2*100;else if(n.contains("liability")||n.contains("gap")||n.contains("volatility"))s=100-v*100;else if(n.contains("drawdown"))s=100+v*100;else if(n.contains("dispersion"))s=100-v*100;else if(n.contains("stability"))s=v*100;else if(n.contains("count")||n.contains("years"))s=v/10*100;else s=50+(f.direction()==FactorDirection.HIGHER_IS_BETTER?v*10:f.direction()==FactorDirection.LOWER_IS_BETTER?-v*10:0);return BigDecimal.valueOf(Math.max(0,Math.min(100,s))).setScale(2,RoundingMode.HALF_UP);}
}
