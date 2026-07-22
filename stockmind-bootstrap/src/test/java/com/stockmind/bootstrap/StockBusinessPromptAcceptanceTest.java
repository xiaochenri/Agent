package com.stockmind.bootstrap;

import com.agent.javascope.prompt.AgentBusinessPromptCustomizer;

/** 验证股票提示词只描述分析协议，不再堆积禁止句式和固定文案。 */
public final class StockBusinessPromptAcceptanceTest {
    public static void main(String[] args) {
        AgentBusinessPromptCustomizer prompts =
                new StockAgentBusinessConfiguration().stockPromptCustomizer();
        String action = prompts.customizeActionPrompt("base");
        String plan = prompts.customizePlanPrompt("base");
        String validation = prompts.customizeValidationPrompt("base");

        require(action.contains("analysis_signals")
                        && action.contains("investment_theses")
                        && action.contains("analysis_agenda")
                        && action.contains("支持因素、反对因素、未知因素"),
                "行动提示词没有使用业务分析中间层");
        require(action.contains("预期信息增量")
                        && action.contains("不是固定调用链")
                        && action.contains("不能仅因某个字段 NOT_AVAILABLE"),
                "行动提示词没有使用议题权重和证据能力控制调查深度");
        require(action.contains("investment_stance")
                        && action.contains("conclusion_evidence")
                        && action.contains("fact、source_step、source_type、as_of 和 basis"),
                "最终结论没有连接投资立场和可读论据");
        require(plan.contains("业务信号") && plan.contains("投资论点")
                        && plan.contains("analysis_agenda")
                        && plan.contains("不预设必须遍历某组工具"),
                "计划仍围绕工具清单而不是投资论点组织");
        require(validation.contains("业务中间层的 stance")
                        && validation.contains("conclusion_evidence")
                        && validation.contains("事实、预测、观点和未知状态"),
                "校验提示词没有围绕结构化分析协议");

        String combined = action + plan + validation;
        require(!combined.contains("prohibited_claims")
                        && !combined.contains("claim_permissions")
                        && !combined.contains("follow_up_policy")
                        && !combined.contains("不得声称")
                        && !combined.contains("不能证明"),
                "股票业务提示词仍残留禁止句式或指定性策略");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
