package com.agent.javascope.verifier;

import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.model.ModelCallException;
import com.agent.javascope.model.ModelRequest;
import com.agent.javascope.model.ModelResult;
import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.json.AgentJsonCodecUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class IndependentVerifierService {

    private final AgentPromptProvider promptProvider;
    private final AgentChatModelClient modelClient;
    private final AgentJsonCodecUtil json;

    public IndependentVerifierService(
            AgentPromptProvider promptProvider, AgentChatModelClient modelClient, AgentJsonCodecUtil json) {
        this.promptProvider = promptProvider;
        this.modelClient = modelClient;
        this.json = json;
    }

    public VerifierResult verify(
            String input,
            List<PlanStepDefinition> plan,
            List<AgentExecutionLogEntry> executionLog,
            Map<String, Object> finalAnswer) {
        List<AcceptanceCriterion> acceptance = buildAcceptance(finalAnswer);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("user_input", input);
        task.put("plan_size", plan == null ? 0 : plan.size());

        String prompt = promptProvider.buildIndependentValidationPrompt(
                json.toJson(task), json.toJson(acceptance), json.toJson(executionLog), json.toJson(finalAnswer));
        VerifierResult parsed = json.convert(modelContent(modelClient.chat(new ModelRequest(prompt))), VerifierResult.class);
        return normalizeResult(parsed);
    }

    private List<AcceptanceCriterion> buildAcceptance(Map<String, Object> finalAnswer) {
        List<AcceptanceCriterion> acceptance = new ArrayList<>();
        acceptance.add(new AcceptanceCriterion("A1", "core_conclusions 必须是数组且非空", "blocking"));
        acceptance.add(new AcceptanceCriterion(
                "A2",
                "key_evidence 必须是数组且可追溯到 execution_log（允许总结表达，不要求逐字引用；若提供 key_evidence_refs 则以 refs 命中为准）",
                "blocking"));
        acceptance.add(new AcceptanceCriterion("A3", "risk_points 与 next_actions 为数组", "non_blocking"));
        if (finalAnswer == null || finalAnswer.isEmpty()) {
            acceptance.add(new AcceptanceCriterion("A4", "final_answer 不能为空", "blocking"));
        }
        return acceptance;
    }

    private Object modelContent(ModelResult result) {
        if (result instanceof ModelResult.Success success) {
            return success.content();
        }
        if (result instanceof ModelResult.Failure failure) {
            throw new ModelCallException(failure.error());
        }
        throw new ModelCallException(null);
    }

    private VerifierResult normalizeResult(VerifierResult result) {
        VerifierResult safe = result == null ? new VerifierResult() : result;
        if (!"pass".equals(safe.getVerdict()) && !"fail".equals(safe.getVerdict())) {
            safe.setVerdict("fail");
        }
        if (safe.getSummary().isBlank()) {
            safe.setSummary("独立验证器未返回有效 summary");
        }
        if (safe.getChecks().isEmpty()) {
            VerifierCheck check = new VerifierCheck();
            check.setId("schema_guard");
            check.setName("验证器输出结构检查");
            check.setLevel("blocking");
            check.setResult("fail");
            check.setReason("checks 为空，按失败处理");
            safe.setChecks(List.of(check));
            safe.setVerdict("fail");
        }
        VerifierNextAction nextAction = safe.getNextAction();
        if (nextAction.getCategory().isBlank()) {
            nextAction.setCategory("add_evidence");
        }
        if (nextAction.getInstruction().isBlank()) {
            nextAction.setInstruction("补充证据后重新验证");
        }
        safe.setNextAction(nextAction);
        return safe;
    }
}
