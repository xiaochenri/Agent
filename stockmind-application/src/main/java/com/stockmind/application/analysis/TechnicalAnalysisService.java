package com.stockmind.application.analysis;

import com.stockmind.application.market.HistoricalBarsQuery;
import com.stockmind.application.market.MarketDataProvider;
import com.stockmind.domain.market.AdjustmentMode;
import com.stockmind.domain.market.BarDataset;
import com.stockmind.domain.market.BarInterval;
import com.stockmind.domain.market.MarketBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic technical indicator calculations over normalized daily OHLCV bars.
 */
public class TechnicalAnalysisService {
    private final MarketDataProvider marketDataProvider;

    public TechnicalAnalysisService(MarketDataProvider marketDataProvider) {
        this.marketDataProvider = marketDataProvider;
    }

    public Map<String, Object> historicalBars(String symbol, String start, String end, String adjustment) {
        BarDataset dataset = loadRequested(symbol, start, end, adjustment);
        List<Map<String, Object>> bars = new ArrayList<>();
        for (MarketBar bar : dataset.bars()) {
            bars.add(Map.of("time", bar.closeTime().toString(), "open", n(bar.open()), "high", n(bar.high()),
                    "low", n(bar.low()), "close", n(bar.close()), "volume", bar.volume().longValue()));
        }
        Map<String, Object> result = base(dataset);
        result.put("bars", bars);
        result.put("bar_count", bars.size());
        return result;
    }

    /**
     * Returns the latest close from the same bar provider used by every technical indicator.
     */
    public Map<String, Object> marketQuote(String symbol, String end, String adjustment) {
        BarDataset dataset = loadRequested(symbol, null, end, adjustment);
        List<MarketBar> bars = dataset.bars();
        MarketBar current = bars.getLast();
        MarketBar previous = bars.size() > 1 ? bars.get(bars.size() - 2) : null;
        double changePct = previous == null ? 0
                : (current.close().doubleValue() - previous.close().doubleValue())
                / previous.close().doubleValue() * 100;
        Map<String, Object> result = base(dataset);
        result.put("price", n(current.close()));
        result.put("change_pct", n(changePct));
        result.put("volume", current.volume().longValue());
        result.put("as_of", current.closeTime().toString());
        return result;
    }

    public Map<String, Object> movingAverage(String symbol, String start, String end, String adjustment, int fast, int slow) {
        validatePeriods(fast, slow);
        BarDataset dataset = loadForIndicators(symbol, start, end, adjustment);
        double[] closes = closes(dataset);
        double[] fastEma = ema(closes, fast);
        double[] slowEma = ema(closes, slow);
        int i = closes.length - 1;
        List<Map<String, Object>> signals = new ArrayList<>();
        if (i > 0 && valid(fastEma[i - 1], slowEma[i - 1]) && valid(fastEma[i], slowEma[i])) {
            if (fastEma[i - 1] <= slowEma[i - 1] && fastEma[i] > slowEma[i])
                signals.add(signal("MA_BULLISH_CROSS", "BULLISH", "短期 EMA 上穿长期 EMA", dataset, i));
            if (fastEma[i - 1] >= slowEma[i - 1] && fastEma[i] < slowEma[i])
                signals.add(signal("MA_BEARISH_CROSS", "BEARISH", "短期 EMA 下穿长期 EMA", dataset, i));
        }
        String state = !valid(fastEma[i], slowEma[i]) ? "INSUFFICIENT_BARS"
                : fastEma[i] > slowEma[i] ? "FAST_ABOVE_SLOW" : "FAST_BELOW_SLOW";
        Map<String, Object> result = base(dataset);
        result.put("indicator", "moving_average");
        result.put("parameters", Map.of("fast_period", fast, "slow_period", slow, "type", "EMA"));
        result.put("latest", values("close", n(closes[i]), "fast_ema", n(fastEma[i]), "slow_ema", n(slowEma[i]), "state", state));
        result.put("warmup_bars", slow);
        result.put("signals", signals);
        return result;
    }

    public Map<String, Object> macd(String symbol, String start, String end, String adjustment, int fast, int slow, int signal) {
        validatePeriods(fast, slow);
        if (signal < 2 || signal > 100) throw new IllegalArgumentException("signal_period 必须在 2 到 100 之间");
        BarDataset dataset = loadForIndicators(symbol, start, end, adjustment);
        double[] dif = subtract(ema(closes(dataset), fast), ema(closes(dataset), slow));
        double[] dea = ema(dif, signal);
        double[] histogram = subtract(dif, dea);
        int i = dif.length - 1;
        List<Map<String, Object>> signals = new ArrayList<>();
        if (i > 0 && valid(dif[i - 1], dea[i - 1]) && valid(dif[i], dea[i])) {
            if (dif[i - 1] <= dea[i - 1] && dif[i] > dea[i])
                signals.add(signal("MACD_BULLISH_CROSS", "BULLISH", "DIF 上穿 DEA", dataset, i));
            if (dif[i - 1] >= dea[i - 1] && dif[i] < dea[i])
                signals.add(signal("MACD_BEARISH_CROSS", "BEARISH", "DIF 下穿 DEA", dataset, i));
        }
        String state = !valid(dif[i], dea[i]) ? "INSUFFICIENT_BARS" : dif[i] > dea[i]
                ? (dif[i] > 0 && dea[i] > 0 ? "ABOVE_SIGNAL_ABOVE_ZERO" : "ABOVE_SIGNAL")
                : (dif[i] < 0 && dea[i] < 0 ? "BELOW_SIGNAL_BELOW_ZERO" : "BELOW_SIGNAL");
        Map<String, Object> result = base(dataset);
        result.put("indicator", "MACD");
        result.put("parameters", Map.of("fast_period", fast, "slow_period", slow, "signal_period", signal));
        result.put("latest", values("dif", n(dif[i]), "dea", n(dea[i]), "histogram", n(histogram[i]), "histogram_scale", 1, "state", state));
        result.put("warmup_bars", slow + signal - 2);
        result.put("signals", signals);
        result.put("limitations", List.of("MACD 在震荡区可能频繁交叉；结果是技术信号，不是买卖指令。"));
        return result;
    }

    public Map<String, Object> rsi(String symbol, String start, String end, String adjustment, int period) {
        if (period < 2 || period > 250) throw new IllegalArgumentException("period 必须在 2 到 250 之间");
        BarDataset dataset = loadForIndicators(symbol, start, end, adjustment);
        double[] rsi = rsi(closes(dataset), period);
        int i = rsi.length - 1;
        List<Map<String, Object>> signals = new ArrayList<>();
        if (i > 0 && valid(rsi[i - 1], rsi[i])) {
            if (rsi[i - 1] <= 70 && rsi[i] > 70)
                signals.add(signal("RSI_ENTER_OVERBOUGHT", "BULLISH", "RSI 进入超买区，反映强劲动量而非确定反转", dataset, i));
            if (rsi[i - 1] >= 30 && rsi[i] < 30)
                signals.add(signal("RSI_ENTER_OVERSOLD", "BEARISH", "RSI 进入超卖区，反映弱势动量而非确定反转", dataset, i));
            if (rsi[i - 1] <= 50 && rsi[i] > 50)
                signals.add(signal("RSI_ABOVE_MIDLINE", "BULLISH", "RSI 上穿 50 中轴", dataset, i));
            if (rsi[i - 1] >= 50 && rsi[i] < 50)
                signals.add(signal("RSI_BELOW_MIDLINE", "BEARISH", "RSI 下穿 50 中轴", dataset, i));
        }
        Map<String, Object> result = base(dataset);
        result.put("indicator", "RSI");
        result.put("parameters", Map.of("period", period, "overbought", 70, "oversold", 30, "smoothing", "WILDER"));
        result.put("latest", values("rsi", n(rsi[i]), "state", !valid(rsi[i]) ? "INSUFFICIENT_BARS" : rsi[i] > 70 ? "OVERBOUGHT" : rsi[i] < 30 ? "OVERSOLD" : "NEUTRAL"));
        result.put("warmup_bars", period + 1);
        result.put("signals", signals);
        return result;
    }

    public Map<String, Object> bollinger(String symbol, String start, String end, String adjustment, int period, double deviations) {
        if (period < 2 || period > 250 || deviations <= 0) throw new IllegalArgumentException("BOLL 参数不合法");
        BarDataset dataset = loadForIndicators(symbol, start, end, adjustment);
        double[] close = closes(dataset), mid = sma(close, period), upper = nan(close.length), lower = nan(close.length), bw = nan(close.length), pb = nan(close.length);
        for (int i = period - 1; i < close.length; i++) {
            double variance = 0;
            for (int j = i - period + 1; j <= i; j++) variance += Math.pow(close[j] - mid[i], 2);
            variance /= period;
            double std = Math.sqrt(variance);
            upper[i] = mid[i] + deviations * std;
            lower[i] = mid[i] - deviations * std;
            bw[i] = mid[i] == 0 ? 0 : (upper[i] - lower[i]) / mid[i] * 100;
            pb[i] = upper[i] == lower[i] ? .5 : (close[i] - lower[i]) / (upper[i] - lower[i]);
        }
        int i = close.length - 1;
        List<Map<String, Object>> signals = new ArrayList<>();
        if (i > 0 && valid(upper[i - 1], lower[i - 1], upper[i], lower[i])) {
            if (close[i - 1] <= upper[i - 1] && close[i] > upper[i])
                signals.add(signal("BOLL_UPPER_BREAK", "BULLISH", "收盘价向上突破上轨，表示相对强势与波动变化", dataset, i));
            if (close[i - 1] >= lower[i - 1] && close[i] < lower[i])
                signals.add(signal("BOLL_LOWER_BREAK", "BEARISH", "收盘价向下突破下轨，表示相对弱势与波动变化", dataset, i));
        }
        Map<String, Object> result = base(dataset);
        result.put("indicator", "BOLLINGER_BANDS");
        result.put("parameters", Map.of("period", period, "deviations", deviations, "standard_deviation", "population"));
        result.put("latest", values("upper", n(upper[i]), "middle", n(mid[i]), "lower", n(lower[i]), "bandwidth", n(bw[i]), "percent_b", n(pb[i])));
        result.put("warmup_bars", period);
        result.put("signals", signals);
        return result;
    }

    public Map<String, Object> atr(String symbol, String start, String end, String adjustment, int period) {
        if (period < 2 || period > 250) throw new IllegalArgumentException("period 必须在 2 到 250 之间");
        BarDataset dataset = loadForIndicators(symbol, start, end, adjustment);
        List<MarketBar> bars = dataset.bars();
        double[] tr = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            double hi = bars.get(i).high().doubleValue(), lo = bars.get(i).low().doubleValue();
            tr[i] = i == 0 ? hi - lo : Math.max(hi - lo, Math.max(Math.abs(hi - bars.get(i - 1).close().doubleValue()), Math.abs(lo - bars.get(i - 1).close().doubleValue())));
        }
        double[] atr = wilder(tr, period);
        int i = bars.size() - 1;
        double atrp = valid(atr[i]) ? atr[i] / bars.get(i).close().doubleValue() * 100 : Double.NaN;
        Map<String, Object> result = base(dataset);
        result.put("indicator", "ATR");
        result.put("parameters", Map.of("period", period, "smoothing", "WILDER"));
        result.put("latest", values("atr", n(atr[i]), "atr_percent", n(atrp), "direction", "NEUTRAL"));
        result.put("warmup_bars", period + 1);
        result.put("signals", List.of());
        result.put("limitations", List.of("ATR 仅衡量波动，不表示价格方向。"));
        return result;
    }

    public Map<String, Object> volume(String symbol, String start, String end, String adjustment, int period) {
        if (period < 2 || period > 250) throw new IllegalArgumentException("period 必须在 2 到 250 之间");
        BarDataset dataset = loadForIndicators(symbol, start, end, adjustment);
        List<MarketBar> bars = dataset.bars();
        double[] volumes = new double[bars.size()], obv = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            volumes[i] = bars.get(i).volume().doubleValue();
            if (i > 0)
                obv[i] = obv[i - 1] + (bars.get(i).close().compareTo(bars.get(i - 1).close()) > 0 ? volumes[i] : bars.get(i).close().compareTo(bars.get(i - 1).close()) < 0 ? -volumes[i] : 0);
        }
        double[] avg = sma(volumes, period);
        int i = bars.size() - 1;
        double ratio = valid(avg[i]) && avg[i] != 0 ? volumes[i] / avg[i] : Double.NaN;
        List<Map<String, Object>> signals = new ArrayList<>();
        if (valid(ratio) && ratio >= 2)
            signals.add(signal("VOLUME_SIGNIFICANT_EXPANSION", "NEUTRAL", "成交量达到 20 日均量两倍以上", dataset, i));
        else if (valid(ratio) && ratio >= 1.5)
            signals.add(signal("VOLUME_EXPANSION", "NEUTRAL", "成交量明显高于均量", dataset, i));
        Map<String, Object> result = base(dataset);
        result.put("indicator", "VOLUME_OBV");
        result.put("parameters", Map.of("period", period));
        result.put("latest", values("volume", n(volumes[i]), "volume_ma", n(avg[i]), "volume_ratio", n(ratio), "obv", n(obv[i])));
        result.put("warmup_bars", period);
        result.put("signals", signals);
        return result;
    }

    public Map<String, Object> technicalIndicatorSnapshot(String symbol, String start, String end, String adjustment) {
        Map<String, Object> ma = movingAverage(symbol, start, end, adjustment, 20, 60);
        Map<String, Object> macd = macd(symbol, start, end, adjustment, 12, 26, 9);
        Map<String, Object> rsi = rsi(symbol, start, end, adjustment, 14);
        Map<String, Object> bollinger = bollinger(symbol, start, end, adjustment, 20, 2);
        Map<String, Object> atr = atr(symbol, start, end, adjustment, 14);
        Map<String, Object> volume = volume(symbol, start, end, adjustment, 20);
        String trend = directionFromMa((Map<?, ?>) ma.get("latest"));
        String momentum = directionFromMacd((Map<?, ?>) macd.get("latest"));
        Map<String, Object> result = new LinkedHashMap<>();
        copyDatasetMetadata(ma, result);
        result.put("indicator", "TECHNICAL_INDICATOR_SNAPSHOT");
        result.put("moving_average", ma.get("latest"));
        result.put("macd", macd.get("latest"));
        result.put("rsi", rsi.get("latest"));
        result.put("bollinger", bollinger.get("latest"));
        result.put("atr", atr.get("latest"));
        result.put("volume_indicator", volume.get("latest"));
        result.put("trend", Map.of("direction", trend, "evidence", List.of("EMA20/EMA60")));
        result.put("momentum", Map.of("direction", momentum,
                "evidence", List.of("MACD", "RSI=" + ((Map<?, ?>) rsi.get("latest")).get("rsi"))));
        result.put("volatility", ((Map<?, ?>) atr.get("latest")).get("atr_percent"));
        result.put("volume", ((Map<?, ?>) volume.get("latest")).get("volume_ratio"));
        result.put("signals", mergeSignals(ma, macd, rsi, bollinger, atr, volume));
        result.put("limitations", List.of("综合方向表示指标的一致性，不是上涨概率或投资建议。"));
        return result;
    }

    /**
     * Compatibility alias for internal callers during migration.
     */
    public Map<String, Object> summary(String symbol, String start, String end, String adjustment) {
        return technicalIndicatorSnapshot(symbol, start, end, adjustment);
    }

    /**
     * Loads the one-month decision window returned by market and K-line tools.
     */
    private BarDataset loadRequested(String symbol, String start, String end, String adjustment) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol 不能为空");
        LocalDate today = LocalDate.now();
        LocalDate requestedEnd = parseDate(end, today);
        LocalDate requestedStart = parseDate(start, requestedEnd.minusDays(90));
        if (requestedStart.isAfter(requestedEnd)) throw new IllegalArgumentException("start_date 不能晚于 end_date");
        boolean staleRange = requestedEnd.isBefore(today.minusDays(7));
        LocalDate endDate = staleRange ? today : requestedEnd;
        LocalDate startDate = staleRange ? endDate.minusDays(30) : requestedStart;
        BarDataset dataset = marketDataProvider.loadBars(new HistoricalBarsQuery(
                symbol.trim().toUpperCase(Locale.ROOT), BarInterval.DAY_1, startDate, endDate,
                parseAdjustment(adjustment)));
        if (dataset.bars().isEmpty()) {
            throw new IllegalArgumentException("指定时间范围内没有可用的已收盘K线");
        }
        if (!staleRange) {
            return dataset;
        }
        List<String> warnings = new ArrayList<>(dataset.warnings());
        warnings.add("请求截止日期 " + requestedEnd + " 已自动更新为当前日期 " + endDate + " 的近一个月决策窗口。");
        return new BarDataset(dataset.datasetId(), dataset.bars(), dataset.source(), dataset.asOf(), List.copyOf(warnings));
    }

    /**
     * Loads extra history for indicator warm-up while keeping raw K-line output compact.
     */
    private BarDataset loadForIndicators(String symbol, String start, String end, String adjustment) {
        BarDataset decisionWindow = loadRequested(symbol, start, end, adjustment);
        LocalDate endDate = decisionWindow.bars().getLast().closeTime().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate lookbackStart = decisionWindow.bars().getFirst().openTime().atZone(java.time.ZoneOffset.UTC)
                .toLocalDate().minusDays(150);
        BarDataset dataset = marketDataProvider.loadBars(new HistoricalBarsQuery(
                decisionWindow.bars().getFirst().instrumentId(), BarInterval.DAY_1, lookbackStart, endDate,
                decisionWindow.bars().getFirst().adjustment()));
        List<String> warnings = new ArrayList<>(decisionWindow.warnings());
        warnings.add("技术指标已使用额外历史数据完成预热计算。");
        return new BarDataset(dataset.datasetId(), dataset.bars(), dataset.source(), dataset.asOf(), List.copyOf(warnings));
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalDate.parse(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式必须为 yyyy-MM-dd");
        }
    }

    private AdjustmentMode parseAdjustment(String value) {
        try {
            return value == null || value.isBlank() ? AdjustmentMode.FORWARD : AdjustmentMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("adjustment 仅支持 NONE、FORWARD、BACKWARD");
        }
    }

    private Map<String, Object> base(BarDataset d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", d.bars().isEmpty() ? "" : d.bars().getFirst().instrumentId());
        m.put("dataset_id", d.datasetId());
        m.put("source", d.source());
        m.put("as_of", d.bars().isEmpty() ? d.asOf().toString() : d.bars().getLast().closeTime().toString());
        m.put("start_at", d.bars().isEmpty() ? "" : d.bars().getFirst().openTime().toString());
        m.put("end_at", d.bars().isEmpty() ? "" : d.bars().getLast().closeTime().toString());
        m.put("interval", "1d");
        m.put("adjustment", d.bars().isEmpty() ? "" : d.bars().getFirst().adjustment().name());
        m.put("warnings", d.warnings());
        return m;
    }

    private double[] closes(BarDataset d) {
        return d.bars().stream().mapToDouble(b -> b.close().doubleValue()).toArray();
    }

    private void validatePeriods(int fast, int slow) {
        if (fast < 2 || slow > 250 || fast >= slow)
            throw new IllegalArgumentException("必须满足 2 <= fast_period < slow_period <= 250");
    }

    private double[] nan(int length) {
        double[] a = new double[length];
        java.util.Arrays.fill(a, Double.NaN);
        return a;
    }

    private double[] sma(double[] a, int p) {
        double[] out = nan(a.length);
        for (int i = p - 1; i < a.length; i++) {
            double sum = 0;
            for (int j = i - p + 1; j <= i; j++) sum += a[j];
            out[i] = sum / p;
        }
        return out;
    }

    private double[] ema(double[] a, int p) {
        double[] out = nan(a.length);
        int first = 0;
        while (first < a.length && !valid(a[first])) first++;
        if (a.length - first < p) return out;
        double seed = 0;
        for (int i = first; i < first + p; i++) {
            if (!valid(a[i])) return out;
            seed += a[i];
        }
        int seedIndex = first + p - 1;
        out[seedIndex] = seed / p;
        double alpha = 2d / (p + 1);
        for (int i = seedIndex + 1; i < a.length; i++) {
            if (valid(a[i])) out[i] = alpha * a[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    private double[] wilder(double[] a, int p) {
        double[] out = nan(a.length);
        if (a.length <= p) return out;
        double sum = 0;
        for (int i = 1; i <= p; i++) sum += a[i];
        out[p] = sum / p;
        for (int i = p + 1; i < a.length; i++) out[i] = (out[i - 1] * (p - 1) + a[i]) / p;
        return out;
    }

    private double[] rsi(double[] close, int p) {
        double[] out = nan(close.length);
        if (close.length <= p) return out;
        double gain = 0, loss = 0;
        for (int i = 1; i <= p; i++) {
            double d = close[i] - close[i - 1];
            gain += Math.max(d, 0);
            loss += Math.max(-d, 0);
        }
        gain /= p;
        loss /= p;
        out[p] = rsiValue(gain, loss);
        for (int i = p + 1; i < close.length; i++) {
            double d = close[i] - close[i - 1];
            gain = (gain * (p - 1) + Math.max(d, 0)) / p;
            loss = (loss * (p - 1) + Math.max(-d, 0)) / p;
            out[i] = rsiValue(gain, loss);
        }
        return out;
    }

    private double rsiValue(double gain, double loss) {
        if (loss == 0 && gain > 0) return 100;
        if (gain == 0 && loss > 0) return 0;
        if (gain == 0) return 50;
        return 100 - 100 / (1 + gain / loss);
    }

    private double[] subtract(double[] a, double[] b) {
        double[] out = nan(a.length);
        for (int i = 0; i < a.length; i++) if (valid(a[i], b[i])) out[i] = a[i] - b[i];
        return out;
    }

    private boolean valid(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private Object n(double value) {
        return valid(value) ? BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP) : null;
    }

    private Object n(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private Map<String, Object> signal(String code, String direction, String explanation, BarDataset d, int i) {
        return Map.of("code", code, "direction", direction, "observed_at", d.bars().get(i).closeTime().toString(), "explanation", explanation);
    }

    private String directionFromMa(Map<?, ?> latest) {
        String state = String.valueOf(latest.get("state"));
        if (state.contains("FAST_ABOVE")) return "BULLISH";
        if (state.contains("FAST_BELOW")) return "BEARISH";
        return "NEUTRAL";
    }

    private String directionFromMacd(Map<?, ?> latest) {
        String state = String.valueOf(latest.get("state"));
        if (state.contains("ABOVE_SIGNAL")) return "BULLISH";
        if (state.contains("BELOW_SIGNAL")) return "BEARISH";
        return "NEUTRAL";
    }

    private Map<String, Object> values(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put(String.valueOf(entries[i]), entries[i + 1]);
        return result;
    }

    private void copyDatasetMetadata(Map<String, Object> source, Map<String, Object> target) {
        for (String key : List.of("symbol", "dataset_id", "source", "as_of", "start_at", "end_at", "interval", "adjustment", "warnings")) {
            target.put(key, source.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mergeSignals(Map<String, Object>... results) {
        List<Map<String, Object>> signals = new ArrayList<>();
        for (Map<String, Object> result : results) {
            Object value = result.get("signals");
            if (value instanceof List<?> items) {
                for (Object item : items)
                    if (item instanceof Map<?, ?> signal) signals.add((Map<String, Object>) signal);
            }
        }
        return signals;
    }
}
