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
        MarketBar peak = first;
        MarketBar drawdownPeak = first;
        MarketBar drawdownTrough = first;
        double maxDrawdownPct = 0;
        double upVolume = 0, downVolume = 0;
        int upDays = 0, downDays = 0;
        List<Map<String, Object>> dailyMoves = new java.util.ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            MarketBar bar = bars.get(i);
            if (bar.low().compareTo(lowest.low()) < 0) lowest = bar;
            if (bar.high().compareTo(highest.high()) > 0) highest = bar;
            if (bar.volume().compareTo(highestVolume.volume()) > 0) highestVolume = bar;
            if (bar.high().compareTo(peak.high()) > 0) peak = bar;
            double drawdown = peak.high().signum() == 0 ? 0
                    : (bar.low().doubleValue() / peak.high().doubleValue() - 1) * 100;
            if (drawdown < maxDrawdownPct) {
                maxDrawdownPct = drawdown;
                drawdownPeak = peak;
                drawdownTrough = bar;
            }
            if (i > 0) {
                MarketBar previous = bars.get(i - 1);
                double change = previous.close().signum() == 0 ? 0
                        : (bar.close().doubleValue() / previous.close().doubleValue() - 1) * 100;
                if (change > 0) { upDays++; upVolume += bar.volume().doubleValue(); }
                if (change < 0) { downDays++; downVolume += bar.volume().doubleValue(); }
                dailyMoves.add(Map.of("time", bar.closeTime().toString(), "change_pct", number(change),
                        "volume", number(bar.volume())));
            }
        }
        dailyMoves.sort((a, b) -> ((BigDecimal) a.get("change_pct")).compareTo((BigDecimal) b.get("change_pct")));

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
        summary.put("max_drawdown_pct", number(maxDrawdownPct));
        summary.put("drawdown_peak_time", drawdownPeak.closeTime().toString());
        summary.put("drawdown_peak_price", number(drawdownPeak.high()));
        summary.put("drawdown_trough_time", drawdownTrough.closeTime().toString());
        summary.put("drawdown_trough_price", number(drawdownTrough.low()));
        double rebound = drawdownTrough.low().signum() == 0 ? 0
                : (lastClose / drawdownTrough.low().doubleValue() - 1) * 100;
        summary.put("rebound_from_trough_pct", number(rebound));
        summary.put("up_day_count", upDays);
        summary.put("down_day_count", downDays);
        summary.put("average_up_day_volume", number(upDays == 0 ? 0 : upVolume / upDays));
        summary.put("average_down_day_volume", number(downDays == 0 ? 0 : downVolume / downDays));
        summary.put("down_vs_up_volume_ratio", number(upDays == 0 || upVolume == 0 ? 0
                : (downDays == 0 ? 0 : downVolume / downDays) / (upVolume / upDays)));
        summary.put("largest_down_days", dailyMoves.stream().limit(3).toList());
        summary.put("largest_up_days", dailyMoves.reversed().stream().limit(3).toList());
        return summary;
    }

    private static BigDecimal number(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal number(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
