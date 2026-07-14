package com.agent.javascope.agent.finalization;

import com.agent.javascope.agent.routing.AgentToolCallExtractor;
import com.agent.javascope.agent.runtime.RuntimeState;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.entity.execution.AgentToolCall;
import com.agent.javascope.entity.plan.PlanStepState;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.plan.PlanStepStatus;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.tools.validation.StepValidatorTool;
import com.agent.javascope.verifier.IndependentVerifierService;
import com.agent.javascope.verifier.VerifierCheck;
import com.agent.javascope.verifier.VerifierNextAction;
import com.agent.javascope.verifier.VerifierResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责最终答案的提取、验证、兜底生成和轮次耗尽后的最终合成。
 */
public class FinalAnswerSynthesizer {

    /** 控制最终答案验证开关和最大轮次。 */
    private final AgentRuntimeProperties properties;
    /** JSON 解析、转换和摘要输出辅助工具。 */
    private final AgentJsonCodecUtil json;
    /** 独立验证器，用于判断 final_answer 是否满足任务与证据。 */
    private final IndependentVerifierService independentVerifierService;
    /** 最终合成时用来阻止模型继续请求工具。 */
    private final AgentToolCallExtractor toolCallExtractor;

    public FinalAnswerSynthesizer(
            AgentRuntimeProperties properties,
            AgentJsonCodecUtil json,
            IndependentVerifierService independentVerifierService,
            AgentToolCallExtractor toolCallExtractor) {
        this.properties = properties;
        this.json = json;
        this.independentVerifierService = independentVerifierService;
        this.toolCallExtractor = toolCallExtractor;
    }

    /**
     * 处理模型本轮直接给出的 final_answer；验证失败时写入反馈并允许主循环继续。
     */
    public boolean handleModelFinalAnswer(String input, int round, RuntimeState state) {
        state.lastFinalAnswer = json.asMap(state.lastResponse.get("final_answer"));
        if (!properties.isFinalAnswerValidationEnabled()) {
            if (state.lastFinalAnswer == null || state.lastFinalAnswer.isEmpty()) {
                state.lastFinalAnswer = buildFallbackFinalAnswer(input, state);
            }
            state.blockedReason = blockedReason(state.lastFinalAnswer, state.executionLog);
            return true;
        }
        state.lastValidation = validateFinalAnswer(input, state.latestPlan, state.executionLog, state.lastFinalAnswer);
        if (state.lastValidation.passed()) {
            state.blockedReason = blockedReason(state.lastFinalAnswer, state.executionLog);
            return true;
        }
        handleValidationFailure(round, state);
        return false;
    }

    /**
     * 最大 reasoning 轮次耗尽后的统一收口逻辑：先尝试最终合成，否则生成 fallback。
     */
    public void handleRoundsExhausted(String input, RuntimeState state, ReasoningCallback reasoningCallback) {
        if (tryFinalSynthesis(input, state, reasoningCallback)) {
            state.blockedReason = blockedReason(state.lastFinalAnswer, state.executionLog);
            return;
        }

        if (!properties.isFinalAnswerValidationEnabled()) {
            state.blockedReason = "推理轮次耗尽，未能生成可用最终答案";
            state.riskFlags.add("reasoning_rounds_exhausted");
            if (state.lastFinalAnswer == null || state.lastFinalAnswer.isEmpty()) {
                state.lastFinalAnswer = buildFallbackFinalAnswer(input, state);
            }
            return;
        }

        state.blockedReason = "结果验证连续失败: " + String.join("; ", state.lastValidation.reasons());
        state.riskFlags.add("result_validation_exhausted");
        if (state.lastFinalAnswer == null || state.lastFinalAnswer.isEmpty()) {
            state.lastFinalAnswer = buildFallbackFinalAnswer(input, state);
        }
    }

    /**
     * 给直答等已结束路径补齐 blockedReason。
     */
    public void applyBlockedReason(RuntimeState state) {
        state.blockedReason = blockedReason(state.lastFinalAnswer, state.executionLog);
    }

    /**
     * 澄清阶段模型未按协议输出 final_answer 时的兜底回复。
     */
    public Map<String, Object> buildClarificationFinalAnswerFallback(Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("core_conclusions", List.of("关键信息缺失，已暂停后续执行，等待你补充后继续。"));
        List<String> missingFields = json.asStringList(data.get("missing_fields"));
        output.put("key_evidence", missingFields.isEmpty()
                ? List.of("缺失字段未识别，请补充完成任务所需的关键信息。")
                : List.of("缺失信息：" + String.join("、", missingFields)));
        output.put("risk_points", List.of("在缺少关键信息时继续执行会产生错误结论风险。"));
        output.put("next_actions", List.of("请先补充缺失信息后再继续。"));
        output.put("awaiting_clarification", true);
        return output;
    }

    /**
     * 无论计划是否已建立或已终态，轮次耗尽后都再触发一次禁止工具调用的最终汇总。
     */
    private boolean tryFinalSynthesis(String input, RuntimeState state, ReasoningCallback reasoningCallback) {
        String executionMode = state.routeDecision.getExecutionMode();
        if ("planned".equals(executionMode) && isPlanTerminal(state)) {
            state.validationFeedback = "计划执行已结束。请基于全部执行日志输出 final_answer，不要继续调用工具。";
        } else if ("react".equals(executionMode)) {
            state.validationFeedback = "ReAct 推理轮次已耗尽。请仅基于已有观察输出保守的 final_answer，"
                    + "明确证据、局限和后续建议，不要继续调用工具。";
        } else {
            state.validationFeedback = "推理轮次已耗尽或计划尚未完成。请仅基于已有执行日志输出保守的 final_answer，"
                    + "明确证据、局限和后续建议，不要继续调用工具。";
        }
        int finalSynthesisRound = properties.resolveMaxRounds(executionMode) + 1;
        // 最终合成是硬状态：reasoning 会看到空工具列表，不再依赖提示词劝模型停止调用工具。
        state.finalSynthesisStage = true;
        state.lastResponse = reasoningCallback.reason(finalSynthesisRound);
        List<AgentToolCall> toolCalls = toolCallExtractor.extract(state.lastResponse);
        if (!toolCalls.isEmpty()) {
            state.riskFlags.add("final_synthesis_tool_call_unexpected");
            return false;
        }
        state.lastFinalAnswer = json.asMap(state.lastResponse.get("final_answer"));
        if (!properties.isFinalAnswerValidationEnabled()) {
            return state.lastFinalAnswer != null && !state.lastFinalAnswer.isEmpty();
        }
        state.lastValidation = validateFinalAnswer(input, state.latestPlan, state.executionLog, state.lastFinalAnswer);
        if (!state.lastValidation.passed()) {
            handleValidationFailure(finalSynthesisRound, state);
            return false;
        }
        return true;
    }

    /**
     * 调用独立验证器，并把验证器结果规整为 ValidationResult。
     */
    private ValidationResult validateFinalAnswer(
            String input,
            List<PlanStepDefinition> plan,
            List<AgentExecutionLogEntry> executionLog,
            Map<String, Object> finalAnswer) {
        VerifierResult result = independentVerifierService.verify(input, plan, executionLog, finalAnswer);
        List<String> reasons = new ArrayList<>();
        for (VerifierCheck check : result.getChecks()) {
            if (!"fail".equals(check.getResult())) {
                continue;
            }
            reasons.add(check.getId() + ":" + check.getReason());
        }
        if (reasons.isEmpty() && "fail".equals(result.getVerdict())) {
            reasons = List.of(json.normalize(result.getSummary(), "独立验证器判定失败"));
        }
        boolean passed = "pass".equals(result.getVerdict());
        VerifierNextAction nextAction = result.getNextAction();
        boolean suggestReplan = !passed && !"none".equals(json.normalize(nextAction.getCategory(), "none"));
        return new ValidationResult(
                passed,
                reasons,
                suggestReplan,
                result.getSummary(),
                result.getChecks(),
                result.getEvidence(),
                result.getWarnings(),
                result.getNextAction());
    }

    /**
     * 将验证失败信息写回 state，下一轮 reasoning 会基于这些反馈修正。
     */
    private void handleValidationFailure(int round, RuntimeState state) {
        state.validationFeedback = buildValidationFeedback(state.lastValidation, state.routeDecision.getExecutionMode());
        state.riskFlags.add("final_answer_validation_failed_round_" + round);
        if (state.lastValidation.suggestReplan()) {
            state.riskFlags.add("final_answer_suggest_replan_round_" + round);
        }
        Map<String, Object> validationOutput = new LinkedHashMap<>();
        validationOutput.put("passed", false);
        validationOutput.put("summary", state.lastValidation.summary());
        validationOutput.put("reasons", state.lastValidation.reasons());
        validationOutput.put("suggest_replan", state.lastValidation.suggestReplan());
        validationOutput.put("checks", state.lastValidation.checks());
        validationOutput.put("evidence", state.lastValidation.evidence());
        validationOutput.put("warnings", state.lastValidation.warnings());
        validationOutput.put("next_action", state.lastValidation.nextAction());
        state.executionLog.add(new AgentExecutionLogEntry(
                "validate_result_round_" + round,
                "independent_verifier",
                Map.of("final_answer", state.lastFinalAnswer),
                validationOutput,
                0.2));
    }

    /**
     * 将验证器结构化结果压缩成模型可读的修正提示。
     */
    private String buildValidationFeedback(ValidationResult validation, String executionMode) {
        List<String> feedback = new ArrayList<>(validation.reasons());
        if (!validation.summary().isBlank()) {
            feedback.add("summary=" + validation.summary());
        }
        if (validation.nextAction() != null
                && !json.normalize(validation.nextAction().getInstruction(), "").isBlank()) {
            feedback.add("next_action=" + validation.nextAction().getInstruction());
        }
        feedback.add("suggest_replan=" + validation.suggestReplan());
        if (validation.suggestReplan()) {
            if ("planned".equals(executionMode)) {
                feedback.add("请优先调用 revise_plan 修正当前计划；若当前没有计划，请调用 create_plan 补齐证据");
            } else if ("react".equals(executionMode)) {
                feedback.add("请根据验证缺口选择新的业务工具补齐证据；不要创建计划，也不要重复相同 tool+input");
            } else {
                feedback.add("当前 direct 证据不足，请给出保守结论并明确局限，不要创建计划");
            }
            feedback.add("key_evidence 可为总结表达，不要求逐字引用工具原文；但必须可映射到 execution_log，建议补充 key_evidence_refs");
        }
        return String.join("; ", feedback);
    }

    /**
     * 根据最终答案和工具执行结果判断是否存在阻塞原因。
     */
    private String blockedReason(Map<String, Object> finalAnswer, List<AgentExecutionLogEntry> executionLog) {
        if (finalAnswer == null || finalAnswer.isEmpty()) {
            return "最终答案为空";
        }
        boolean hasToolResult = executionLog.stream()
                .map(item -> normalizeStatus(json.asMap(item.getOutput()).get("status")))
                .anyMatch(status -> !status.isEmpty());
        if (!hasToolResult) {
            return "";
        }
        boolean anySuccess = executionLog.stream()
                .map(item -> normalizeStatus(json.asMap(item.getOutput()).get("status")))
                .anyMatch("success"::equals);
        if (!anySuccess) {
            return "所有工具步骤均失败，无法继续给出高置信结论";
        }
        return "";
    }

    /**
     * 规整工具结果中的 status 字段。
     */
    private String normalizeStatus(Object status) {
        return status == null ? "" : String.valueOf(status).trim();
    }

    /**
     * 无法得到高置信最终答案时，基于已有执行日志生成保守兜底答案。
     */
    private Map<String, Object> buildFallbackFinalAnswer(String input, RuntimeState state) {
        List<String> conclusions = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> nextActions = new ArrayList<>();

        conclusions.add("当前推理轮次已用尽，暂时无法完成全部验证。建议下一步二选一：1）补充关键信息后继续；2）按保守默认策略先给出初步结论与风险清单。");

        for (AgentExecutionLogEntry log : state.executionLog) {
            String toolName = json.normalize(log.getToolName(), "");
            if ("reasoning".equals(toolName)
                    || "result_verifier".equals(toolName)
                    || "independent_verifier".equals(toolName)
                    || StepValidatorTool.TOOL_NAME.equals(toolName)) {
                continue;
            }
            Map<String, Object> output = json.asMap(log.getOutput());
            String status = json.normalize((String) output.get("status"), "");
            if ("success".equals(status)) {
                evidence.add("工具 " + toolName + " 执行成功，输出摘要: " + summarizeOutput(output));
            } else if ("failed".equals(status)) {
                risks.add("工具 " + toolName + " 执行失败: " + summarizeOutput(output));
            }
        }

        if (evidence.isEmpty()) {
            evidence.add("当前轮次未获得可直接复用的高置信工具结果。");
        }
        if (risks.isEmpty()) {
            risks.add("结果未经完整验证，结论可能不完整。");
        }
        if (state.blockedReason != null && !state.blockedReason.isBlank()) {
            risks.add(state.blockedReason);
        }
        nextActions.add("选项1：补充当前任务缺少的关键信息后重试。");
        nextActions.add("选项2：在现有证据下按保守策略输出初步结论，并明确不确定性。");

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("core_conclusions", conclusions);
        fallback.put("key_evidence", evidence);
        fallback.put("risk_points", risks);
        fallback.put("next_actions", nextActions);
        fallback.put("fallback", true);
        fallback.put("original_question", input);
        return fallback;
    }

    /**
     * 提取工具输出中最适合放进 fallback 证据/风险的摘要。
     */
    private String summarizeOutput(Map<String, Object> output) {
        Map<String, Object> data = json.asMap(output.get("data"));
        if (!data.isEmpty()) {
            return json.toJson(data);
        }
        List<String> errors = json.asStringList(output.get("validation_errors"));
        if (!errors.isEmpty()) {
            return String.join("; ", errors);
        }
        return json.toJson(output);
    }

    /**
     * 所有计划步骤都不再处于 pending/in_progress 时视为计划终态。
     */
    private boolean isPlanTerminal(RuntimeState state) {
        if (state.planSteps.isEmpty()) {
            return false;
        }
        for (PlanStepState step : state.planSteps) {
            if (step.getStatus() == PlanStepStatus.PENDING || step.getStatus() == PlanStepStatus.IN_PROGRESS) {
                return false;
            }
        }
        return true;
    }
}
