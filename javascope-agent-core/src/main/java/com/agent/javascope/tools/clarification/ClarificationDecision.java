package com.agent.javascope.tools.clarification;

/**
 * 澄清策略的决策结果，供工具主流程决定是否暂停、默认执行或直接执行。
 */
record ClarificationDecision(
        /** ask、execute_with_guess、direct_execute 或 confirm_before_action。 */
        String action,
        /** 给模型和调用方看的决策原因。 */
        String reasoning,
        /** P0/P1/P2，表示澄清优先级和阻断等级。 */
        String priority,
        /** true 表示必须等待用户补充或确认。 */
        boolean requiresUserResponse) {
}
