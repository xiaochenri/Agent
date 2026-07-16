package com.agent.javascope.agent.runtime;

import com.agent.javascope.agent.prompt.DefaultPromptAssembler;
import com.agent.javascope.context.budget.PromptBudget;
import com.agent.javascope.context.projection.ContextRequest;
import com.agent.javascope.context.projection.InMemoryContextManager;
import com.agent.javascope.context.projection.WorkingContext;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.prompt.DefaultAgentPromptProvider;
import com.agent.javascope.tool.error.DefaultToolErrorClassifier;
import com.agent.javascope.tool.runtime.ToolError;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolExecutionStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Map;

/**
 * 无测试框架依赖的错误恢复闭环验收程序。
 *
 * <p>可通过 Maven test-compile 后直接运行 main；覆盖 direct/react/planned 共用记录语义、
 * 改参恢复、工具不可用恢复、活跃失败投影及预算裁剪可达性。</p>
 */
public final class ToolFailureRecoveryAcceptanceTest {

    private final AgentJsonCodecUtil json = new AgentJsonCodecUtil();

    public static void main(String[] args) {
        new ToolFailureRecoveryAcceptanceTest().run();
    }

    private void run() {
        verifyAllExecutionModesRecordFinalFailure();
        verifyModifiedInputAndDependencyRecovery();
        verifyActiveFailuresSurviveProjectionAndPromptBudget();
    }

    private void verifyAllExecutionModesRecordFinalFailure() {
        for (String mode : List.of("direct", "react", "planned")) {
            RuntimeState state = new RuntimeState(null);
            Map<String, Object> input = Map.of("symbol", "");
            ToolFailureTracker.recordResult(state, 2, mode + "_tool_step", "stock_quote",
                    input, failed("TOOL_INPUT_INVALID", "股票代码不能为空"), json);
            require(state.activeToolFailures.size() == 1, mode + " 未记录最终失败");
            require(state.blockedActionFingerprints.contains(
                    ToolFailureTracker.fingerprint("stock_quote", input, json)),
                    mode + " 未阻止相同失败调用");
        }
    }

    private void verifyModifiedInputAndDependencyRecovery() {
        RuntimeState state = new RuntimeState(null);
        Map<String, Object> invalidInput = Map.of("symbol", "");
        Map<String, Object> correctedInput = Map.of("symbol", "600519");
        ToolFailureTracker.recordResult(state, 1, "direct_tool_step", "stock_quote",
                invalidInput, failed("TOOL_INPUT_INVALID", "股票代码不能为空"), json);
        require(!state.blockedActionFingerprints.contains(
                ToolFailureTracker.fingerprint("stock_quote", correctedInput, json)),
                "修改输入后仍被错误阻断");

        ToolFailureTracker.recordResult(state, 2, "react_tool_step", "knowledge_search",
                Map.of("query", "现金流"),
                failed("VECTOR_STORE_UNAVAILABLE", "知识库依赖暂时不可用"), json);
        require(state.unavailableTools.contains("knowledge_search"), "依赖失败未标记工具不可用");
        ToolFailureTracker.recordResult(state, 3, "react_tool_step", "knowledge_search",
                Map.of("query", "恢复探测"), success(), json);
        require(!state.unavailableTools.contains("knowledge_search"), "成功探测后未解除工具不可用状态");
    }

    private void verifyActiveFailuresSurviveProjectionAndPromptBudget() {
        RuntimeState state = new RuntimeState(null);
        ToolFailureTracker.recordResult(state, 1, "planned_step_1", "financial_report",
                Map.of("period", "1900Q1"), failed("REPORT_NOT_FOUND", "未找到指定期间的财报"), json);
        ToolFailureTracker.recordResult(state, 2, "planned_step_2", "financial_report",
                Map.of("period", "1900Q2"), failed("REPORT_NOT_FOUND", "未找到指定期间的财报"), json);

        ContextRequest request = new ContextRequest(
                json.toTree(List.of()), json.toTree(List.of()), json.toTree(List.of()),
                json.toTree(List.of()), json.toTree(state.activeToolFailures.values().stream()
                        .map(ToolFailureRecord::toPublicMap).toList()),
                "必须遵循活跃失败恢复动作", json.toTree(List.of()));
        WorkingContext context = new InMemoryContextManager().project(request, new PromptBudget(1_000, 1, 1));
        require(context.activeToolFailures().size() == 2, "活跃失败被 latest-tool 去重或 evidence 窗口裁掉");

        String prompt = new DefaultPromptAssembler(json).assembleActionPrompt(
                new DefaultAgentPromptProvider(), "系统规则", "查询财报", "planned", List.of(),
                context, new PromptBudget(1_000, 1, 1));
        require(prompt.contains("active_tool_failures"), "预算裁剪后活跃失败区域不可达");
        require(prompt.contains("REPORT_NOT_FOUND"), "预算裁剪后错误码不可达");
        require(prompt.contains("必须遵循活跃失败恢复动作"), "预算裁剪后当前约束不可达");

        String reactPrompt = new DefaultPromptAssembler(json).assembleActionPrompt(
                new DefaultAgentPromptProvider(), "系统规则", "分析下跌原因", "react", List.of(),
                context, new PromptBudget(10_000, 1, 1));
        require(reactPrompt.contains("\"decision_summary\""), "ReAct Prompt 缺少轻量决策摘要协议");
        require(reactPrompt.contains("\"hypothesis_updates\""), "ReAct 决策摘要缺少判断更新字段");
        require(reactPrompt.contains("\"next_information_needed\""), "ReAct 决策摘要缺少下一信息缺口");
    }

    private ToolExecutionResult failed(String code, String message) {
        ToolError error = DefaultToolErrorClassifier.INSTANCE.classify(code, message, false);
        return new ToolExecutionResult(
                "test_tool", ToolExecutionStatus.FAILED, false, List.of(), List.of(message),
                false, error.code(), JsonNodeFactory.instance.nullNode(),
                JsonNodeFactory.instance.objectNode().put("attempt_count", 1), error);
    }

    private ToolExecutionResult success() {
        return new ToolExecutionResult(
                "test_tool", ToolExecutionStatus.SUCCESS, true, List.of(), List.of(),
                false, "", JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(), null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
