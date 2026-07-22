package com.stockmind.application.factor;

import com.stockmind.application.snapshot.PointInTimeStockSnapshot;
import com.stockmind.application.snapshot.PointInTimeStockSnapshotService;
import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorCategoryResult;
import com.stockmind.domain.factor.FactorQuality;
import com.stockmind.domain.factor.FactorScore;
import com.stockmind.domain.factor.FactorWarning;
import com.stockmind.domain.factor.InvestmentStyle;
import com.stockmind.domain.factor.StockFactorProfile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates snapshot loading, six-category calculation and policy-based scoring. */
public final class StockFactorProfileService {
    private static final BigDecimal POSITIVE_CATEGORY_SCORE = BigDecimal.valueOf(65);
    private static final BigDecimal NEGATIVE_CATEGORY_SCORE = BigDecimal.valueOf(40);

    private final PointInTimeStockSnapshotService snapshots;
    private final List<FactorCalculator> calculators;
    private final FactorScoringService scoring = new FactorScoringService();
    private final InvestmentAssessmentPolicy assessmentPolicy = new InvestmentAssessmentPolicy();

    /**
     * Creates a profile service with an explicit calculator set. Copying the list prevents
     * runtime mutation from changing the scoring dimensions of an in-flight request.
     */
    public StockFactorProfileService(
            PointInTimeStockSnapshotService snapshots, List<FactorCalculator> calculators) {
        this.snapshots = snapshots;
        this.calculators = List.copyOf(calculators);
    }

    /**
     * Builds a point-in-time factor profile for the requested investment style.
     * Scores and coverage are business-calculated outputs; callers should explain rather
     * than recompute them. Missing and low-sample factors are surfaced as limitations.
     */
    public StockFactorProfile analyze(String symbol, LocalDate asOf, InvestmentStyle style) {
        PointInTimeStockSnapshot snapshot = snapshots.load(symbol, asOf);
        Map<FactorCategory, FactorCategoryResult> categories = new EnumMap<>(FactorCategory.class);
        for (FactorCalculator calculator : calculators) {
            categories.put(calculator.category(), calculator.calculate(snapshot));
        }

        FactorPolicy policy = FactorPolicy.of(style);
        var scored = scoring.score(categories, policy);
        List<String> positive = new ArrayList<>();
        List<String> negative = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        Set<FactorWarning> warnings = EnumSet.noneOf(FactorWarning.class);
        Set<String> sources = new LinkedHashSet<>();

        for (var entry : categories.entrySet()) {
            entry.getValue().warnings().forEach(warnings::add);
            entry.getValue().factors().forEach(value -> {
                warnings.addAll(value.warnings());
                sources.addAll(value.sources());
                if (value.quality() == FactorQuality.MISSING
                        || value.quality() == FactorQuality.LOW_SAMPLE) {
                    limitations.add(value.name() + ":" + value.limitation());
                }
            });

            FactorScore categoryScore = scored.categoryScores().get(entry.getKey());
            if (categoryScore != null && categoryScore.score() != null) {
                if (categoryScore.score().compareTo(POSITIVE_CATEGORY_SCORE) >= 0) {
                    positive.add(entry.getKey() + " category_score=" + categoryScore.score());
                }
                if (categoryScore.score().compareTo(NEGATIVE_CATEGORY_SCORE) < 0) {
                    negative.add(entry.getKey() + " category_score=" + categoryScore.score());
                }
            }
            classifySignals(entry.getValue().signals(), positive, negative);
        }

        snapshot.providerFailures().forEach((key, value) -> limitations.add(key + "=" + value));
        limitations.addAll(snapshot.industryPeers().limitations());
        return new StockFactorProfile(
                snapshot.requestId(),
                snapshot.instrument().normalizedSymbol(),
                snapshot.instrument().name(),
                snapshot.requestedAsOf(),
                snapshot.marketPrice().effectiveTradeDate(),
                policy.version(),
                style,
                scored.coverage(),
                scored.overallScore(),
                categories,
                scored.categoryScores(),
                assessmentPolicy.evaluate(scored.coverage(), scored.overallScore(),
                        categories, scored.categoryScores()),
                positive,
                negative,
                List.copyOf(warnings),
                limitations,
                List.copyOf(sources));
    }

    /** Keeps descriptive states out of the positive bucket and classifies only clear directions. */
    private void classifySignals(
            List<String> signals, List<String> positive, List<String> negative) {
        for (String signal : signals) {
            if (containsAny(signal,
                    "growth_state=ACCELERATING",
                    "growth_state=RECOVERING",
                    "forecast_growth_state=STABLE_OR_GROWING",
                    "expectation_revision_state=UPGRADED",
                    "expectation_revision_state=STRONGLY_UPGRADED",
                    "trend_state=UPTREND",
                    "trend_state=STRONG_UPTREND")) {
                positive.add(signal);
            } else if (containsAny(signal,
                    "growth_state=DECELERATING",
                    "growth_state=TURNING_NEGATIVE",
                    "forecast_growth_state=TURNING_NEGATIVE",
                    "expectation_revision_state=DOWNGRADED",
                    "expectation_revision_state=STRONGLY_DOWNGRADED",
                    "trend_state=DOWNTREND",
                    "trend_state=STRONG_DOWNTREND")) {
                negative.add(signal);
            }
        }
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}
