package com.agent.javascope.tool.runtime;

import java.util.List;

/**
 * 业务模块可选实现：为通用澄清工具提供领域化能力说明、示例回复与补充问题。
 */
public interface ClarificationBusinessProvider {

    default List<String> capabilities(String userInput) {
        return List.of();
    }

    default List<String> suggestedReplies(String userInput) {
        return List.of();
    }

    default List<String> extraQuestions(String userInput, List<String> missingFields) {
        return List.of();
    }

    default String nextStepHint(String userInput, List<String> missingFields) {
        return "";
    }

    /** 为指定槽位提供领域候选项。 */
    default List<String> slotCandidates(String userInput, String slotName) {
        return List.of();
    }

    /** 基于用户输入和记忆给出领域默认值。 */
    default String defaultValue(String userInput, String slotName, String memory) {
        return "";
    }

    /**
     * 业务侧可标记高风险动作，要求进入 confirm_before_action 暂停确认。
     */
    default boolean confirmBeforeAction(String userInput, List<String> missingFields) {
        return false;
    }
}
