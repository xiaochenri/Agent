package com.stockmind.bootstrap;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 股票业务统一的财报期间归一化器，供安全校验、业务工具和语义校验共同复用。 */
final class FinancialReportPeriodResolver {

    private static final Pattern QUARTER = Pattern.compile(
            "(?i)(20\\d{2})\\s*(?:年)?\\s*(?:Q([1-4])|第?([一二三四1234])季度|([一二三])季报|年报|年度报告|年度财报)");

    private FinancialReportPeriodResolver() {}

    static Optional<ResolvedPeriod> resolve(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            LocalDate date = LocalDate.parse(normalized);
            return fromDate(date);
        } catch (DateTimeParseException ignored) {
            // 非 ISO 日期继续按自然语言财报期间解析。
        }
        Matcher matcher = QUARTER.matcher(normalized);
        if (!matcher.find()) return Optional.empty();
        int year = Integer.parseInt(matcher.group(1));
        String matched = matcher.group();
        int quarter;
        if (matched.contains("年报") || matched.contains("年度")) {
            quarter = 4;
        } else if (matcher.group(2) != null) {
            quarter = Integer.parseInt(matcher.group(2));
        } else {
            String token = matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            quarter = chineseQuarter(token);
        }
        return Optional.of(forQuarter(year, quarter));
    }

    static Optional<ResolvedPeriod> findInText(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        Matcher iso = Pattern.compile("20\\d{2}-(?:03-31|06-30|09-30|12-31)").matcher(text);
        if (iso.find()) return resolve(iso.group());
        Matcher matcher = QUARTER.matcher(text);
        return matcher.find() ? resolve(matcher.group()) : Optional.empty();
    }

    private static Optional<ResolvedPeriod> fromDate(LocalDate date) {
        int quarter = switch (date.getMonthValue()) {
            case 3 -> date.getDayOfMonth() == 31 ? 1 : 0;
            case 6 -> date.getDayOfMonth() == 30 ? 2 : 0;
            case 9 -> date.getDayOfMonth() == 30 ? 3 : 0;
            case 12 -> date.getDayOfMonth() == 31 ? 4 : 0;
            default -> 0;
        };
        return quarter == 0 ? Optional.empty() : Optional.of(forQuarter(date.getYear(), quarter));
    }

    private static ResolvedPeriod forQuarter(int year, int quarter) {
        LocalDate date = switch (quarter) {
            case 1 -> LocalDate.of(year, 3, 31);
            case 2 -> LocalDate.of(year, 6, 30);
            case 3 -> LocalDate.of(year, 9, 30);
            default -> LocalDate.of(year, 12, 31);
        };
        return new ResolvedPeriod(date.toString(), quarter == 4 ? "ANNUAL" : quarter == 2 ? "H1" : "Q" + quarter);
    }

    private static int chineseQuarter(String token) {
        return switch (token) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            default -> 4;
        };
    }

    record ResolvedPeriod(String reportPeriod, String reportType) {}
}
