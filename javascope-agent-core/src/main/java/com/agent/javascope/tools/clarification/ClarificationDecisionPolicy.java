package com.agent.javascope.tools.clarification;

/**
 * 澄清动作选择策略：把槽位识别结果和风险信号映射成运行时动作。
 */
class ClarificationDecisionPolicy {

    /**
     * 根据关键槽位、不可逆动作和分析意图选择 ask/confirm/guess/direct。
     */
    ClarificationDecision decide(
            boolean hasTarget,
            boolean hasTimeWindow,
            boolean isIrreversibleAction,
            boolean needsConfirmation,
            boolean analysisIntent) {
        if (!hasTarget || (isIrreversibleAction && !hasTarget)) {
            return new ClarificationDecision(
                    "ask",
                    "P0（致命缺失）：缺少关键操作对象，继续执行存在错误风险，需一次性澄清关键槽位。",
                    "P0",
                    true);
        }
        if (needsConfirmation) {
            return new ClarificationDecision(
                    "confirm_before_action",
                    "P0（高风险确认）：请求包含不可逆或外部副作用动作，执行前必须得到用户明确确认。",
                    "P0",
                    true);
        }
        if (!hasTimeWindow && analysisIntent) {
            return new ClarificationDecision(
                    "execute_with_guess",
                    "P1（模糊歧义）：时间范围未给出，先按长期偏好或通用默认值执行，并附带轻量确认。",
                    "P1",
                    false);
        }
        return new ClarificationDecision(
                "direct_execute",
                "P2（风格/偏好缺失）：关键路径信息完整，非关键缺失采用默认偏好自动填充。",
                "P2",
                false);
    }
}
