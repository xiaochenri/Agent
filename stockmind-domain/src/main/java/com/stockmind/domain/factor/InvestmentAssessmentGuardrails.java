package com.stockmind.domain.factor;

import java.util.List;
import java.util.Map;

/** 旧因子评分流程内部使用的数据完整性判断；模型侧结论由业务分析中间层提供。 */
public record InvestmentAssessmentGuardrails(
        String assessmentReadiness,
        InvestmentConclusionStrength maximumConclusionStrength,
        boolean directionalConclusionAllowed,
        Map<String, Boolean> claimPermissions,
        List<String> requiredGaps) {

    public InvestmentAssessmentGuardrails {
        claimPermissions = claimPermissions == null ? Map.of() : Map.copyOf(claimPermissions);
        requiredGaps = requiredGaps == null ? List.of() : List.copyOf(requiredGaps);
    }
}
