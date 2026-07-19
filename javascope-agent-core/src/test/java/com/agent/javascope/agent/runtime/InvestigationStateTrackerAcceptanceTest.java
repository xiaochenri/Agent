package com.agent.javascope.agent.runtime;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.json.AgentJsonCodecUtil;

import java.util.List;
import java.util.Map;

/** 无测试框架依赖的 ReAct 跨轮调查状态验收程序。 */
public final class InvestigationStateTrackerAcceptanceTest {

    private final AgentJsonCodecUtil json = new AgentJsonCodecUtil();
    private final InvestigationStateTracker tracker = new InvestigationStateTracker(json);

    public static void main(String[] args) {
        new InvestigationStateTrackerAcceptanceTest().run();
    }

    private void run() {
        verifyInitialHypothesesAndCrossRoundMerge();
        verifyInvalidEvidenceReferenceIsDroppedWithoutBlockingAction();
        verifyInitialEmptyContradictionIsAllowed();
        verifyHighestActionableGapCanSkipBlockedGap();
        verifyDeclaredToolMustMatchAndFailedCallIsBlocked();
    }

    private void verifyInitialHypothesesAndCrossRoundMerge() {
        RuntimeState state = new RuntimeState(null);
        InvestigationStateTracker.UpdateResult first = tracker.apply(state, firstRoundResponse(), 1);
        require(first.valid(), "首轮有效调查状态被拒绝: " + first.errors());
        require(list(state.investigationState.get("hypotheses")).size() == 2, "首轮未保存两个候选假设");

        state.executionLog.add(new AgentExecutionLogEntry(
                "tool_call_round_1", "market_quote", Map.of("symbol", "600519"),
                Map.of("status", "success", "validation_passed", true,
                        "data", Map.of("daily_change_pct", -4.6,
                                "start_at", "2026-06-16T01:30:00Z",
                                "end_at", "2026-07-16T07:00:00Z")), 0.9));
        Map<String, Object> secondResponse = mutable(secondRoundResponse());
        child(child(secondResponse, "reasoning_update"), "question_frame").put("time_window", "unknown");
        InvestigationStateTracker.UpdateResult second = tracker.apply(state, secondResponse, 2);
        require(second.valid(), "第二轮有效调查状态被拒绝: " + second.errors());
        require(list(state.investigationState.get("resolved_facts")).size() == 1, "新增事实未跨轮保存");
        require(list(state.investigationState.get("hypotheses")).size() == 2, "未更新的假设在合并时丢失");
        require("2026-06-16至2026-07-16".equals(
                        child(state.investigationState, "question_frame").get("time_window")),
                "工具已返回实际窗口时未修正 unknown 时间口径");
        Map<String, Object> h1 = list(state.investigationState.get("hypotheses")).stream()
                .filter(item -> "H1".equals(item.get("hypothesis_id"))).findFirst().orElseThrow();
        require("supported".equals(h1.get("status")), "新证据未更新假设状态");
        require(List.of("tool_call_round_1").equals(h1.get("supporting_evidence")),
                "带说明文字的证据引用未规范化为纯 source_step");
    }

    private void verifyInvalidEvidenceReferenceIsDroppedWithoutBlockingAction() {
        RuntimeState state = new RuntimeState(null);
        require(tracker.apply(state, firstRoundResponse(), 1).valid(), "测试前置状态初始化失败");
        Map<String, Object> invalid = mutable(secondRoundResponse());
        Map<String, Object> reasoning = child(invalid, "reasoning_update");
        reasoning.put("new_observations", List.of(Map.of(
                "source_step", "tool_call_round_99",
                "fact", "不存在的事实",
                "reliability", "high",
                "relevance", "不应进入状态")));
        InvestigationStateTracker.UpdateResult result = tracker.apply(state, invalid, 2);
        require(result.valid(), "单个无效证据字段不应阻止整轮合法动作: " + result.errors());
        require(!result.warnings().isEmpty(), "无效证据字段被丢弃时没有留下纠正警告");
        require(list(state.investigationState.get("resolved_facts")).isEmpty(), "无效事实污染了调查状态");
        Map<String, Object> h1 = list(state.investigationState.get("hypotheses")).stream()
                .filter(item -> "H1".equals(item.get("hypothesis_id"))).findFirst().orElseThrow();
        require("open".equals(h1.get("status")), "无有效证据的假设变化不应进入调查状态");
    }

    private void verifyInitialEmptyContradictionIsAllowed() {
        RuntimeState state = new RuntimeState(null);
        Map<String, Object> response = mutable(firstRoundResponse());
        child(response, "reasoning_update").put("contradiction_check", List.of());
        InvestigationStateTracker.UpdateResult result = tracker.apply(state, response, 1);
        require(result.valid(), "首轮尚无证据时空 contradiction_check 不应拒绝整轮: " + result.errors());
    }

    private void verifyHighestActionableGapCanSkipBlockedGap() {
        RuntimeState state = new RuntimeState(null);
        Map<String, Object> response = mutable(firstRoundResponse());
        Map<String, Object> reasoning = child(response, "reasoning_update");
        reasoning.put("ranked_information_gaps", List.of(
                Map.of("gap", "基本面缺口", "priority", 1, "actionable", false,
                        "blocked_reason", "财报工具不可用", "reason", "信息价值最高"),
                Map.of("gap", "事件缺口", "priority", 2, "actionable", true,
                        "blocked_reason", "", "reason", "当前仍可执行")));
        Map<String, Object> decision = child(reasoning, "action_decision");
        decision.put("selected_gap", "事件缺口");
        decision.put("selected_tool", "news_search");
        child(child(response, "selected_action"), "tool_call").put("name", "news_search");
        InvestigationStateTracker.UpdateResult result = tracker.apply(state, response, 1);
        require(result.valid(), "不应强迫选择更重要但不可执行的缺口: " + result.errors());
    }

    private void verifyDeclaredToolMustMatchAndFailedCallIsBlocked() {
        RuntimeState mismatchState = new RuntimeState(null);
        Map<String, Object> mismatch = mutable(firstRoundResponse());
        child(child(mismatch, "reasoning_update"), "action_decision").put("selected_tool", "news_search");
        require(!tracker.apply(mismatchState, mismatch, 1).valid(), "声明工具与实际工具不一致时未被拒绝");

        RuntimeState blockedState = new RuntimeState(null);
        Map<String, Object> blocked = mutable(firstRoundResponse());
        Map<String, Object> toolCall = child(child(blocked, "selected_action"), "tool_call");
        blockedState.blockedActionFingerprints.add(ToolFailureTracker.fingerprint(
                "market_quote", json.asMap(toolCall.get("input")), json));
        require(!tracker.apply(blockedState, blocked, 1).valid(), "已失败的相同 tool+input 未在推理阶段阻止");

        RuntimeState unavailableState = new RuntimeState(null);
        unavailableState.unavailableTools.add("market_quote");
        require(!tracker.apply(unavailableState, firstRoundResponse(), 1).valid(),
                "依赖不可用工具未在推理阶段阻止");
    }

    private Map<String, Object> firstRoundResponse() {
        return response(
                List.of(),
                List.of(
                        hypothesis("H1", "公司基本面恶化", "open", 0.3, List.of(), "首轮建立候选解释"),
                        hypothesis("H2", "行业整体调整", "open", 0.3, List.of(), "首轮建立替代解释")),
                "需要先确认下跌事实与时间范围");
    }

    private Map<String, Object> secondRoundResponse() {
        return response(
                List.of(Map.of(
                        "source_step", "tool_call_round_1",
                        "fact", "近一月下跌约4.6%",
                        "reliability", "high",
                        "relevance", "确认现象但尚不能单独区分原因")),
                List.of(hypothesis("H1", "公司基本面恶化", "supported", 0.5,
                        List.of("tool_call_round_1: 行情确认价格变化"), "新事实提高关注度，但仍需基本面证据")),
                "需要区分个股因素与行业因素");
    }

    private Map<String, Object> response(
            List<Map<String, Object>> observations,
            List<Map<String, Object>> hypotheses,
            String gap) {
        return Map.of(
                "reasoning_update", Map.of(
                        "question_frame", Map.of(
                                "target", "贵州茅台", "phenomenon", "股价下跌",
                                "time_window", "近一个月", "benchmark", "白酒行业"),
                        "new_observations", observations,
                        "hypothesis_updates", hypotheses,
                        "contradiction_check", List.of("目前仍不能排除其他候选解释"),
                        "ranked_information_gaps", List.of(Map.of(
                                "gap", gap, "priority", 1, "actionable", true,
                                "blocked_reason", "", "reason", "最能区分候选解释")),
                        "action_decision", Map.of(
                                "selected_gap", gap,
                                "selected_tool", "market_quote",
                                "why_now", "优先解决区分度最高的缺口",
                                "expected_result_branches", List.of(
                                        Map.of("if", "结果支持公司特异性", "then", "提高 H1 权重"),
                                        Map.of("if", "结果与行业同步", "then", "提高 H2 权重"))),
                        "stop_assessment", Map.of(
                                "should_stop", false, "reason", "候选解释尚未区分")),
                "selected_action", Map.of(
                        "type", "tool_call",
                        "tool_call", Map.of("name", "market_quote", "input", Map.of("symbol", "600519"))),
                "final_answer", Map.of());
    }

    private Map<String, Object> hypothesis(
            String id, String claim, String status, double confidence,
            List<String> supportingEvidence, String reason) {
        return Map.of(
                "hypothesis_id", id,
                "claim", claim,
                "status", status,
                "confidence", confidence,
                "supporting_evidence", supportingEvidence,
                "contradicting_evidence", List.of(),
                "missing_evidence", List.of("仍需区分证据"),
                "update_reason", reason);
    }

    private List<Map<String, Object>> list(Object value) {
        return json.asPlanList(value);
    }

    private Map<String, Object> mutable(Map<String, Object> value) {
        return json.asMap(json.toTree(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> child(Map<String, Object> parent, String field) {
        return (Map<String, Object>) parent.get(field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
