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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IndependentVerifierService {

    private final AgentPromptProvider promptProvider;
    private final AgentChatModelClient modelClient;
    private final AgentJsonCodecUtil json;
    private final List<FinalAnswerSemanticValidator> semanticValidators;

    public IndependentVerifierService(
            AgentPromptProvider promptProvider, AgentChatModelClient modelClient, AgentJsonCodecUtil json) {
        this(promptProvider, modelClient, json, List.of());
    }

    public IndependentVerifierService(
            AgentPromptProvider promptProvider,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json,
            List<FinalAnswerSemanticValidator> semanticValidators) {
        this.promptProvider = promptProvider;
        this.modelClient = modelClient;
        this.json = json;
        this.semanticValidators = semanticValidators == null ? List.of() : List.copyOf(semanticValidators);
    }

    public VerifierResult verify(
            String input,
            List<PlanStepDefinition> plan,
            List<AgentExecutionLogEntry> executionLog,
            Map<String, Object> finalAnswer) {
        List<String> deterministicIssues = new ArrayList<>(
                validateEvidenceContract(executionLog, finalAnswer));
        for (FinalAnswerSemanticValidator validator : semanticValidators) {
            deterministicIssues.addAll(validator.validate(input, executionLog, finalAnswer));
        }
        if (!deterministicIssues.isEmpty()) {
            return deterministicFailure(deterministicIssues);
        }

        List<AcceptanceCriterion> acceptance = buildAcceptance(finalAnswer, executionLog);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("user_input", input);
        task.put("plan_size", plan == null ? 0 : plan.size());

        String prompt = promptProvider.buildIndependentValidationPrompt(
                json.toJson(task), json.toJson(acceptance), json.toJson(executionLog), json.toJson(finalAnswer));
        VerifierResult parsed = json.convert(modelContent(modelClient.chat(new ModelRequest(prompt))), VerifierResult.class);
        return normalizeResult(parsed);
    }

    private List<AcceptanceCriterion> buildAcceptance(
            Map<String, Object> finalAnswer, List<AgentExecutionLogEntry> executionLog) {
        List<AcceptanceCriterion> acceptance = new ArrayList<>();
        acceptance.add(new AcceptanceCriterion("A1", "core_conclusions 必须是数组且非空", "blocking"));
        acceptance.add(new AcceptanceCriterion(
                "A2",
                "key_evidence 必须是数组且可追溯到 execution_log（允许总结表达，不要求逐字引用；若提供 key_evidence_refs 则以 refs 命中为准）",
                "blocking"));
        acceptance.add(new AcceptanceCriterion("A3", "risk_points 与 next_actions 为数组", "non_blocking"));
        if (!successfulToolSteps(executionLog).isEmpty()) {
            acceptance.add(new AcceptanceCriterion(
                    "A5",
                    "conclusion_evidence 必须逐条关联 core_conclusions，并提供可追溯的事实、来源步骤、用户可读来源类型和数据日期",
                    "blocking"));
        }
        if (finalAnswer == null || finalAnswer.isEmpty()) {
            acceptance.add(new AcceptanceCriterion("A4", "final_answer 不能为空", "blocking"));
        }
        return acceptance;
    }

    private List<String> validateEvidenceContract(
            List<AgentExecutionLogEntry> executionLog, Map<String, Object> finalAnswer) {
        List<String> issues = new ArrayList<>();
        List<String> conclusions = json.asStringList(finalAnswer == null ? null : finalAnswer.get("core_conclusions"));
        Set<String> successfulSteps = successfulToolSteps(executionLog);
        if (successfulSteps.isEmpty()) return issues;
        Object rawChains = finalAnswer == null ? null : finalAnswer.get("conclusion_evidence");
        if (!(rawChains instanceof List<?> chains) || chains.isEmpty()) {
            issues.add("final_answer.conclusion_evidence 必须非空，并逐条说明结论依赖的论据");
            return issues;
        }
        Set<String> mappedConclusions = new HashSet<>();
        for (Object rawChain : chains) {
            Map<String, Object> chain = json.asMap(rawChain);
            String conclusion = json.normalize(String.valueOf(chain.getOrDefault("conclusion", "")), "");
            if (conclusion.isBlank() || !conclusions.contains(conclusion)) {
                issues.add("conclusion_evidence.conclusion 必须与 core_conclusions 中的一条完全一致");
                continue;
            }
            mappedConclusions.add(conclusion);
            Object rawEvidence = chain.get("evidence");
            if (!(rawEvidence instanceof List<?> evidenceItems) || evidenceItems.isEmpty()) {
                issues.add("结论“" + conclusion + "”缺少 evidence 数组");
                continue;
            }
            for (Object rawEvidenceItem : evidenceItems) {
                Map<String, Object> evidence = json.asMap(rawEvidenceItem);
                String fact = text(evidence.get("fact"));
                String sourceStep = text(evidence.get("source_step"));
                String sourceType = text(evidence.get("source_type"));
                String asOf = text(evidence.get("as_of"));
                if (fact.isBlank() || sourceType.isBlank() || asOf.isBlank()) {
                    issues.add("每条结论论据必须包含 fact、source_type 和 as_of");
                }
                if (sourceStep.isBlank() || !successfulSteps.contains(sourceStep)) {
                    issues.add("论据 source_step 必须指向校验成功的工具结果: " + sourceStep);
                }
            }
        }
        for (String conclusion : conclusions) {
            if (!mappedConclusions.contains(conclusion)) {
                issues.add("核心结论缺少论据映射: " + conclusion);
            }
        }
        return issues;
    }

    private Set<String> successfulToolSteps(List<AgentExecutionLogEntry> executionLog) {
        Set<String> steps = new HashSet<>();
        if (executionLog == null) return steps;
        for (AgentExecutionLogEntry entry : executionLog) {
            Map<String, Object> output = json.asMap(entry.getOutput());
            if ("success".equals(text(output.get("status")))
                    && json.asBoolean(output.get("validation_passed"), false)) {
                steps.add(entry.getStep());
            }
        }
        return steps;
    }

    private VerifierResult deterministicFailure(List<String> issues) {
        VerifierResult result = new VerifierResult();
        result.setVerdict("fail");
        result.setSummary("最终答案未通过确定性语义与论据映射校验");
        List<VerifierCheck> checks = new ArrayList<>();
        for (int i = 0; i < issues.size(); i++) {
            VerifierCheck check = new VerifierCheck();
            check.setId("deterministic_semantic_" + (i + 1));
            check.setName("确定性语义校验");
            check.setLevel("blocking");
            check.setResult("fail");
            check.setReason(issues.get(i));
            checks.add(check);
        }
        result.setChecks(checks);
        VerifierNextAction nextAction = new VerifierNextAction();
        // These issues can be repaired from existing evidence; do not trigger replanning or more
        // business tool calls merely to rewrite the final answer.
        nextAction.setCategory("none");
        nextAction.setInstruction("仅使用已有结构化工具证据修正结论，并补齐conclusion_evidence后重新验证；不要调用新工具");
        result.setNextAction(nextAction);
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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
