package com.stockmind.application.analysis;

import com.stockmind.domain.market.AdjustmentMode;
import com.stockmind.domain.market.BarInterval;
import com.stockmind.domain.market.MarketBar;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 无测试框架依赖的 K 线业务摘要验收程序。 */
public final class MarketSeriesSummarizerAcceptanceTest {

    public static void main(String[] args) {
        List<MarketBar> bars = List.of(
                bar("2026-06-16T07:00:00Z", 451.82, 456.00, 445.24, 451.82, 4_782_780),
                bar("2026-07-15T07:00:00Z", 560.82, 563.46, 560.82, 562.89, 5_100_067),
                bar("2026-07-16T07:00:00Z", 565.15, 565.15, 533.98, 536.91, 3_785_156));

        Map<String, Object> summary = MarketSeriesSummarizer.summarize(bars);

        require("UP".equals(summary.get("period_direction")), "K 线区间方向摘要错误");
        require(decimal(summary, "period_change_pct").doubleValue() > 18, "K 线区间涨跌摘要错误");
        require(decimal(summary, "last_bar_change_pct").doubleValue() < -4, "末日涨跌摘要错误");
        require(Integer.valueOf(3).equals(summary.get("bar_count")), "K 线数量摘要错误");
        require(decimal(summary, "lowest_low").compareTo(BigDecimal.valueOf(445.24)) == 0,
                "区间最低价摘要错误");
        require(decimal(summary, "highest_high").compareTo(BigDecimal.valueOf(565.15)) == 0,
                "区间最高价摘要错误");
        require(decimal(summary, "highest_volume").compareTo(BigDecimal.valueOf(5_100_067)) == 0,
                "区间最大成交量摘要错误");
    }

    private static MarketBar bar(
            String closeTime, double open, double high, double low, double close, long volume) {
        Instant closeAt = Instant.parse(closeTime);
        return new MarketBar(
                "600519", BarInterval.DAY_1, closeAt.minusSeconds(21_600), closeAt,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high), BigDecimal.valueOf(low),
                BigDecimal.valueOf(close), BigDecimal.valueOf(volume), BigDecimal.ZERO,
                AdjustmentMode.FORWARD);
    }

    private static BigDecimal decimal(Map<String, Object> summary, String field) {
        return (BigDecimal) summary.get(field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
