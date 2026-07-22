package com.stockmind.application.factor;

import com.stockmind.domain.factor.FactorCategory;
import com.stockmind.domain.factor.FactorCategoryResult;
import com.stockmind.domain.factor.FactorCoverage;
import com.stockmind.domain.factor.FactorQuality;
import com.stockmind.domain.factor.FactorScore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/** Applies style weights after each category has been normalized. */
public final class FactorScoringService {
    private static final BigDecimal FULL_COVERAGE = BigDecimal.valueOf(100);
    private static final BigDecimal NORMAL_COVERAGE_GATE = BigDecimal.valueOf(80);
    private static final BigDecimal MINIMUM_OVERALL_GATE = BigDecimal.valueOf(60);

    private final FactorNormalizationService normalizer = new FactorNormalizationService();

    /**
     * Produces category scores, weighted coverage and an optional overall score.
     * Missing data reduces the effective category weight; below 60% total coverage the
     * overall score is deliberately suppressed instead of treating absence as weakness.
     */
    public ScoredProfile score(
            Map<FactorCategory, FactorCategoryResult> results, FactorPolicy policy) {
        Map<FactorCategory, FactorScore> scores = new EnumMap<>(FactorCategory.class);
        Map<FactorCategory, BigDecimal> effectiveWeights = new EnumMap<>(FactorCategory.class);
        BigDecimal coveredWeight = BigDecimal.ZERO;
        BigDecimal totalWeight = policy.weights().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weightedScore = BigDecimal.ZERO;

        for (var entry : results.entrySet()) {
            FactorScore score = normalizer.score(entry.getValue());
            scores.put(entry.getKey(), score);
            BigDecimal configuredWeight = policy.weights().getOrDefault(
                    entry.getKey(), BigDecimal.ZERO);
            BigDecimal effectiveWeight = configuredWeight.multiply(score.coveragePct())
                    .divide(FULL_COVERAGE, 8, RoundingMode.HALF_UP);
            effectiveWeights.put(entry.getKey(), effectiveWeight);
            coveredWeight = coveredWeight.add(effectiveWeight);
            if (score.score() != null) {
                weightedScore = weightedScore.add(score.score().multiply(effectiveWeight));
            }
        }

        BigDecimal coverage = totalWeight.signum() == 0
                ? BigDecimal.ZERO
                : coveredWeight.multiply(FULL_COVERAGE)
                        .divide(totalWeight, 2, RoundingMode.HALF_UP);
        FactorQuality quality = coverage.compareTo(NORMAL_COVERAGE_GATE) >= 0
                ? FactorQuality.VALID
                : coverage.compareTo(MINIMUM_OVERALL_GATE) >= 0
                        ? FactorQuality.PARTIAL
                        : FactorQuality.MISSING;
        BigDecimal overallScore = quality == FactorQuality.MISSING || coveredWeight.signum() == 0
                ? null
                : weightedScore.divide(coveredWeight, 2, RoundingMode.HALF_UP);
        return new ScoredProfile(
                new FactorCoverage(coverage, quality, effectiveWeights), overallScore, scores);
    }

    /** Immutable aggregate returned to the profile orchestrator. */
    public record ScoredProfile(
            FactorCoverage coverage,
            BigDecimal overallScore,
            Map<FactorCategory, FactorScore> categoryScores) {
        public ScoredProfile {
            categoryScores = Map.copyOf(categoryScores);
        }
    }
}
