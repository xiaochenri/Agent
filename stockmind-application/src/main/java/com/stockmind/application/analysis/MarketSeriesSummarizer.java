package com.stockmind.application.analysis;

import com.stockmind.domain.market.MarketBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 股票业务侧的确定性 K 线区间摘要，不向 Agent 框架泄露 OHLCV 领域知识。 */
final class MarketSeriesSummarizer {

    private MarketSeriesSummarizer() {}

    static Map<String, Object> summarize(List<MarketBar> bars) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (bars == null || bars.isEmpty()) return summary;

        MarketBar first = bars.getFirst();
        MarketBar last = bars.getLast();
        double firstClose = first.close().doubleValue();
        double lastClose = last.close().doubleValue();
        double periodChangePct = firstClose == 0 ? 0 : (lastClose / firstClose - 1) * 100;
        double lastBarChangePct = 0;
        if (bars.size() > 1) {
            double previousClose = bars.get(bars.size() - 2).close().doubleValue();
            if (previousClose != 0) lastBarChangePct = (lastClose / previousClose - 1) * 100;
        }

        MarketBar lowest = first;
        MarketBar highest = first;
        MarketBar highestVolume = first;
        for (MarketBar bar : bars) {
            if (bar.low().compareTo(lowest.low()) < 0) lowest = bar;
            if (bar.high().compareTo(highest.high()) > 0) highest = bar;
            if (bar.volume().compareTo(highestVolume.volume()) > 0) highestVolume = bar;
        }

        summary.put("bar_count", bars.size());
        summary.put("start_time", first.closeTime().toString());
        summary.put("end_time", last.closeTime().toString());
        summary.put("first_close", number(first.close()));
        summary.put("last_close", number(last.close()));
        summary.put("period_change_pct", number(periodChangePct));
        summary.put("period_direction", periodChangePct > 0 ? "UP" : periodChangePct < 0 ? "DOWN" : "FLAT");
        summary.put("last_bar_change_pct", number(lastBarChangePct));
        summary.put("lowest_low", number(lowest.low()));
        summary.put("lowest_low_time", lowest.closeTime().toString());
        summary.put("highest_high", number(highest.high()));
        summary.put("highest_high_time", highest.closeTime().toString());
        summary.put("highest_volume", number(highestVolume.volume()));
        summary.put("highest_volume_time", highestVolume.closeTime().toString());
        return summary;
    }

    private static BigDecimal number(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal number(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
