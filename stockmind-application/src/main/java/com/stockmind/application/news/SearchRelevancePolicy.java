package com.stockmind.application.news;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic lexical relevance gate for noisy public search endpoints. */
public final class SearchRelevancePolicy {
    private static final Set<String> GENERIC_TERMS = Set.of(
            "最近", "最新", "分析", "公告", "新闻", "财报", "年报", "业绩",
            "收入", "营收", "利润", "净利润", "毛利率", "同比", "增长",
            "风险", "政策", "非经常性损益");

    private SearchRelevancePolicy() {}

    /**
     * Scores title and summary matches and rejects results that omit the query's leading
     * entity/topic term. This prevents generic finance words from filling the result set.
     */
    public static Match assess(String query, String title, String summary) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) return new Match(false, 0, List.of());
        String normalizedTitle = normalize(title);
        String normalizedSummary = normalize(summary);
        String entity = entityTerm(terms);
        if (!entity.isBlank()
                && !normalizedTitle.contains(entity)
                && !normalizedSummary.contains(entity)) {
            return new Match(false, 0, List.of());
        }
        int score = 0;
        java.util.ArrayList<String> matched = new java.util.ArrayList<>();
        for (String term : terms) {
            if (normalizedTitle.contains(term)) {
                score += term.equals(entity) ? 60 : 12;
                matched.add(term);
            } else if (normalizedSummary.contains(term)) {
                score += term.equals(entity) ? 30 : 6;
                matched.add(term);
            }
        }
        if (score == 0 && terms.stream().allMatch(value -> value.matches("\\d{6}"))) {
            // A pure security-code search cannot be checked against titles that only contain
            // the company name; keep the upstream exact-code result instead of dropping it.
            return new Match(true, 1, List.of());
        }
        return new Match(score > 0, score, List.copyOf(matched));
    }

    static List<String> terms(String query) {
        return Arrays.stream(normalize(query).split("[\\s,，;；]+"))
                .map(String::trim)
                .filter(value -> value.length() >= 2)
                .distinct()
                .toList();
    }

    private static String entityTerm(List<String> terms) {
        return terms.stream()
                .filter(value -> !GENERIC_TERMS.contains(value))
                .filter(value -> !value.matches("\\d+"))
                .filter(value -> !value.matches("\\d{4}年?"))
                .findFirst().orElse("");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .trim();
    }

    /** Machine-readable relevance decision. */
    public record Match(boolean relevant, int score, List<String> matchedTerms) {}
}
