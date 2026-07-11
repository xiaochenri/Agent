package com.agent.javascope.context.budget;

/** 控制本轮模型上下文选择范围的预算参数。 */
public record PromptBudget(
        /** Prompt 总字符上限。 */
        int maxCharacters,
        /** 最多选择的历史事件数。 */
        int maxHistoryItems,
        /** 最多选择的证据摘要数。 */
        int maxEvidenceItems) {

    /** 对预算设置保守下限，避免错误配置生成空上下文。 */
    public PromptBudget {
        maxCharacters = Math.max(1_000, maxCharacters);
        maxHistoryItems = Math.max(1, maxHistoryItems);
        maxEvidenceItems = Math.max(1, maxEvidenceItems);
    }
}
