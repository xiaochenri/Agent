package com.agent.javascope.verifier;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.model.ModelRequest;
import com.agent.javascope.model.ModelResult;
import com.agent.javascope.prompt.DefaultAgentPromptProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Ensures conclusion-to-evidence mapping is checked before the model verifier. */
public final class IndependentVerifierEvidenceContractAcceptanceTest {

    public static void main(String[] args) {
        AgentJsonCodecUtil json = new AgentJsonCodecUtil();
        AtomicInteger modelCalls = new AtomicInteger();
        AgentChatModelClient model = new AgentChatModelClient() {
            @Override
            public ModelResult chat(ModelRequest request) {
                modelCalls.incrementAndGet();
                return new ModelResult.Success(json.toTree(Map.of(
                        "verdict", "pass",
                        "summary", "通过",
                        "checks", List.of(Map.of(
                                "id", "A1", "name", "证据检查", "level", "blocking",
                                "result", "pass", "reason", "映射有效")),
                        "evidence", List.of(),
                        "warnings", List.of(),
                        "next_action", Map.of("category", "none", "instruction", "无需动作"))));
            }

            @Override
            public ModelResult chatStream(ModelRequest request, Consumer<String> deltaConsumer) {
                return chat(request);
            }
        };
        IndependentVerifierService verifier = new IndependentVerifierService(
                new DefaultAgentPromptProvider(), model, json);
        List<AgentExecutionLogEntry> log = List.of(new AgentExecutionLogEntry(
                "tool_call_round_1", "market_quote", Map.of("symbol", "600519"),
                Map.of("status", "success", "validation_passed", true,
                        "data", Map.of("price", 100, "as_of", "2026-07-21")), 0.9));

        Map<String, Object> missingMapping = Map.of(
                "core_conclusions", List.of("当前价格为100元。"),
                "key_evidence", List.of("行情价格100元。"),
                "risk_points", List.of(), "next_actions", List.of());
        VerifierResult rejected = verifier.verify("查询价格", List.of(), log, missingMapping);
        require("fail".equals(rejected.getVerdict()), "缺少conclusion_evidence时未被确定性拒绝");
        require(modelCalls.get() == 0, "确定性契约失败后仍不必要地调用了模型验证器");

        String conclusion = "当前价格为100元。";
        Map<String, Object> mapped = Map.of(
                "core_conclusions", List.of(conclusion),
                "key_evidence", List.of("行情价格100元。"),
                "conclusion_evidence", List.of(Map.of(
                        "conclusion", conclusion,
                        "evidence", List.of(Map.of(
                                "fact", "最新价格100元。",
                                "source_step", "tool_call_round_1",
                                "source_type", "行情",
                                "as_of", "2026-07-21",
                                "basis", "最新交易日行情")),
                        "limitations", List.of())),
                "risk_points", List.of(), "next_actions", List.of());
        VerifierResult accepted = verifier.verify("查询价格", List.of(), log, mapped);
        require("pass".equals(accepted.getVerdict()), "有效的结论论据映射未通过独立验证");
        require(modelCalls.get() == 1, "契约通过后没有进入独立模型验证");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
