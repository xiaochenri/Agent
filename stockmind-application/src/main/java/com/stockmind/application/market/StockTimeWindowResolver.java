package com.stockmind.application.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stock-domain time-window normalization shared by market, technical and news tools.
 *
 * <p>The resolver deliberately lives outside Agent Core: it is a financial-business rule, not a
 * generic agent scheduling concern.</p>
 */
public final class StockTimeWindowResolver {
    private static final int DEFAULT_DECISION_DAYS = 30;
    private static final Pattern EXPLICIT_RANGE = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\s*(?:至|到|~|—|-|to)\\s*(\\d{4}-\\d{2}-\\d{2})",
            Pattern.CASE_INSENSITIVE);

    private StockTimeWindowResolver() {}

    /** Resolves optional structured dates to one shared business time window. */
    public static ResolvedTimeWindow resolve(String start, String end) {
        LocalDate today = LocalDate.now();
        LocalDate requestedEnd = parse(end, today);
        LocalDate requestedStart = parse(start, requestedEnd.minusDays(DEFAULT_DECISION_DAYS));
        if (requestedStart.isAfter(requestedEnd)) {
            throw new IllegalArgumentException("start_date 不能晚于 end_date");
        }
        boolean stale = requestedEnd.isBefore(today.minusDays(7));
        LocalDate resolvedEnd = stale ? today : requestedEnd;
        LocalDate resolvedStart = stale ? resolvedEnd.minusDays(DEFAULT_DECISION_DAYS) : requestedStart;
        List<String> warnings = new ArrayList<>();
        if (stale) {
            warnings.add("请求截止日期 " + requestedEnd + " 已自动更新为当前日期 " + resolvedEnd + " 的近一个月决策窗口。");
        }
        return new ResolvedTimeWindow(resolvedStart, resolvedEnd, List.copyOf(warnings));
    }

    /** Resolves a news time_window string, including the plan format yyyy-MM-dd至yyyy-MM-dd. */
    public static ResolvedTimeWindow resolveNews(String start, String end, String timeWindow) {
        String normalized = timeWindow == null ? "" : timeWindow.trim();
        Matcher matcher = EXPLICIT_RANGE.matcher(normalized);
        if ((start == null || start.isBlank()) && (end == null || end.isBlank()) && matcher.find()) {
            start = matcher.group(1);
            end = matcher.group(2);
        }
        return resolve(start, end);
    }

    private static LocalDate parse(String value, LocalDate fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式必须为 yyyy-MM-dd");
        }
    }

    public record ResolvedTimeWindow(LocalDate startDate, LocalDate endDate, List<String> warnings) {
        public String display() {
            return startDate + "至" + endDate;
        }
    }
}
