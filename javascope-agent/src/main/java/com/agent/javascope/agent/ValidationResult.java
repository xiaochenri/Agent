package com.agent.javascope.agent;

import com.agent.javascope.verifier.VerifierCheck;
import com.agent.javascope.verifier.VerifierEvidence;
import com.agent.javascope.verifier.VerifierNextAction;
import java.util.List;

/**
 * 独立验证器对最终答案的结构化判定结果。
 */
record ValidationResult(
        /** 最终答案是否通过验证。 */
        boolean passed,
        /** 未通过时的原因列表。 */
        List<String> reasons,
        /** 验证器是否建议重新规划或补证据。 */
        boolean suggestReplan,
        /** 验证器给出的摘要说明。 */
        String summary,
        /** 分项检查结果。 */
        List<VerifierCheck> checks,
        /** 支撑验证结论的证据。 */
        List<VerifierEvidence> evidence,
        /** 非阻断风险或提醒。 */
        List<String> warnings,
        /** 建议主流程下一步动作。 */
        VerifierNextAction nextAction) {

    /** 构造一个轻量结果，用于初始化或简单失败场景。 */
    ValidationResult(boolean passed, List<String> reasons, boolean suggestReplan) {
        this(
                passed,
                reasons,
                suggestReplan,
                "",
                List.of(),
                List.of(),
                List.of(),
                new VerifierNextAction());
    }
}
