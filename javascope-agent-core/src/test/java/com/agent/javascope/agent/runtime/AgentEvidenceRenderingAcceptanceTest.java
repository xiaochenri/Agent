package com.agent.javascope.agent.runtime;

import com.agent.javascope.entity.plan.PlanToolData;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import java.util.List;
import java.util.Map;

/** Verifies that conclusion evidence is visible in the clean user response. */
public final class AgentEvidenceRenderingAcceptanceTest {

    public static void main(String[] args) {
        AgentJsonCodecUtil json = new AgentJsonCodecUtil();
        TestAgent agent = new TestAgent(json);
        String conclusion = "药明康德目前具备一定的条件性投资价值。";
        Map<String, Object> finalAnswer = Map.of(
                "core_conclusions", List.of(conclusion),
                "key_evidence", List.of("内部兼容摘要"),
                "conclusion_evidence", List.of(Map.of(
                        "conclusion", conclusion,
                        "evidence", List.of(Map.of(
                                "fact", "TTM市盈率18.61倍，处于近三年52.03%分位。",
                                "source_step", "tool_call_round_1",
                                "source_type", "因子画像",
                                "as_of", "2026-07-21",
                                "basis", "TTM市盈率与近三年历史分位")),
                        "limitations", List.of("盈利归因仍未完全验证"))),
                "risk_points", List.of("增长与现金流仍需跟踪。"),
                "next_actions", List.of("关注半年报。"));
        Map<String, Object> response = json.parseJson(agent.render(finalAnswer));
        Map<String, Object> message = json.asMap(response.get("message"));
        String content = String.valueOf(message.get("content"));
        require(content.contains("结论依据"), "用户消息没有结论依据区块");
        require(content.contains("TTM市盈率18.61倍"), "支持结论的数据未展示");
        require(content.contains("来源：因子画像") && content.contains("日期：2026-07-21")
                        && content.contains("口径：TTM市盈率与近三年历史分位"),
                "论据来源、日期或口径未展示");
        require(content.contains("盈利归因仍未完全验证"), "结论限制未展示");
        require(!content.contains("tool_call_round_1"), "内部追溯步骤泄露到用户正文");
    }

    private static final class TestAgent extends Agent {
        private TestAgent(AgentJsonCodecUtil json) {
            super(new AgentRuntimeProperties(), null, null, null, json);
            this.provider = "test";
            this.model = "test";
        }

        private String render(Map<String, Object> finalAnswer) {
            return respondAsText("test-execution", new PlanToolData(), List.of(), List.of(),
                    List.of(), List.of(), "", finalAnswer, List.of());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
