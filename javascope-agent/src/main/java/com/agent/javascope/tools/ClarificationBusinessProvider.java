package com.agent.javascope.tools;

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
}
