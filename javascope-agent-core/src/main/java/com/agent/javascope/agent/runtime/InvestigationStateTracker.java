package com.agent.javascope.agent.runtime;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.json.AgentJsonCodecUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验并合并 ReAct 每轮输出的结构化调查更新。
 *
 * <p>该状态只保存可审计的事实、假设和决策摘要，不保存模型隐藏思维过程。</p>
 */
public final class InvestigationStateTracker {

    private static final Set<String> HYPOTHESIS_STATUSES =
            Set.of("open", "supported", "weakened", "rejected");
    private static final Set<String> RELIABILITY_LEVELS =
            Set.of("high", "medium", "low");

    private final AgentJsonCodecUtil json;

    public InvestigationStateTracker(AgentJsonCodecUtil json) {
        this.json = json;
    }

    public UpdateResult apply(RuntimeState state, Map<String, Object> response, int round) {
        Map<String, Object> update = json.asMap(response.get("reasoning_update"));
        if (update.isEmpty()) {
            return invalid("react 每轮必须输出非空 reasoning_update");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> candidate = deepCopy(state.investigationState);
        boolean initialRound = state.investigationState.isEmpty();
        mergeQuestionFrame(candidate, update, state, errors);
        Set<String> usableSteps = usableEvidenceSteps(state);
        mergeObservations(candidate, update, usableSteps, warnings);
        mergeHypotheses(candidate, update, usableSteps,
                validObservationSources(update, usableSteps), errors, warnings);
        mergeDecisionState(candidate, update, response, state, initialRound, errors);

        if (!errors.isEmpty()) {
            return new UpdateResult(false, List.copyOf(errors), List.copyOf(warnings));
        }
        candidate.put("last_updated_round", round);
        state.investigationState.clear();
        state.investigationState.putAll(candidate);
        return new UpdateResult(true, List.of(), List.copyOf(warnings));
    }

    private void mergeQuestionFrame(
            Map<String, Object> candidate,
            Map<String, Object> update,
            RuntimeState state,
            List<String> errors) {
        Map<String, Object> incoming = json.asMap(update.get("question_frame"));
        Map<String, Object> existing = json.asMap(candidate.get("question_frame"));
        for (String field : List.of("target", "phenomenon", "time_window", "benchmark")) {
            String value = text(incoming.get(field));
            if (!value.isEmpty()) existing.put(field, value);
        }
        if ("unknown".equalsIgnoreCase(text(existing.get("time_window")))) {
            String observedWindow = latestObservedTimeWindow(state);
            if (!observedWindow.isEmpty()) existing.put("time_window", observedWindow);
        }
        if (text(existing.get("target")).isEmpty() || text(existing.get("phenomenon")).isEmpty()) {
            errors.add("reasoning_update.question_frame 必须包含非空 target 和 phenomenon");
        }
        candidate.put("question_frame", existing);
    }

    /** 工具已返回实际证据窗口时，确定性替换模型遗留的 unknown，避免跨轮时间口径漂移。 */
    private String latestObservedTimeWindow(RuntimeState state) {
        for (int index = state.executionLog.size() - 1; index >= 0; index--) {
            Map<String, Object> output = json.asMap(state.executionLog.get(index).getOutput());
            if (!"success".equals(output.get("status"))
                    || !json.asBoolean(output.get("validation_passed"), false)) {
                continue;
            }
            Map<String, Object> data = json.asMap(output.get("data"));
            String start = firstNonEmpty(text(data.get("start_date")), text(data.get("start_at")));
            String end = firstNonEmpty(text(data.get("end_date")), text(data.get("end_at")));
            if (!start.isEmpty() && !end.isEmpty()) {
                return datePart(start) + "至" + datePart(end);
            }
        }
        return "";
    }

    private String datePart(String value) {
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }

    private void mergeObservations(
            Map<String, Object> candidate,
            Map<String, Object> update,
            Set<String> usableSteps,
            List<String> warnings) {
        List<Map<String, Object>> facts = maps(candidate.get("resolved_facts"));
        Set<String> fingerprints = new LinkedHashSet<>();
        for (Map<String, Object> fact : facts) {
            fingerprints.add(text(fact.get("source_step")) + "|" + text(fact.get("fact")));
        }
        for (Map<String, Object> observation : maps(update.get("new_observations"))) {
            String sourceStep = text(observation.get("source_step"));
            String fact = text(observation.get("fact"));
            String reliability = text(observation.get("reliability"));
            String relevance = text(observation.get("relevance"));
            if (!usableSteps.contains(sourceStep)) {
                warnings.add("已丢弃无效 observation；source_step 不存在或不是校验成功的工具结果: " + sourceStep);
                continue;
            }
            if (fact.isEmpty() || relevance.isEmpty() || !RELIABILITY_LEVELS.contains(reliability)) {
                warnings.add("已丢弃字段不完整的 observation；必须包含 fact、relevance 和 high|medium|low reliability");
                continue;
            }
            if (fingerprints.add(sourceStep + "|" + fact)) {
                facts.add(Map.of(
                        "source_step", sourceStep,
                        "fact", fact,
                        "reliability", reliability,
                        "relevance", relevance));
            }
        }
        candidate.put("resolved_facts", facts);
    }

    private void mergeHypotheses(
            Map<String, Object> candidate,
            Map<String, Object> update,
            Set<String> usableSteps,
            Set<String> currentObservationSources,
            List<String> errors,
            List<String> warnings) {
        List<Map<String, Object>> existingList = maps(candidate.get("hypotheses"));
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> hypothesis : existingList) {
            String id = text(hypothesis.get("hypothesis_id"));
            if (!id.isEmpty()) byId.put(id, new LinkedHashMap<>(hypothesis));
        }

        List<Map<String, Object>> updates = maps(update.get("hypothesis_updates"));
        if (updates.isEmpty()) {
            errors.add("reasoning_update.hypothesis_updates 不能为空；无变化时也要说明保持不变的原因");
        }
        for (Map<String, Object> hypothesisUpdate : updates) {
            String id = text(hypothesisUpdate.get("hypothesis_id"));
            if (id.isEmpty()) {
                errors.add("hypothesis_updates.hypothesis_id 不能为空");
                continue;
            }
            boolean existingHypothesis = byId.containsKey(id);
            Map<String, Object> merged = byId.getOrDefault(id, new LinkedHashMap<>());
            String previousStatus = text(merged.get("status"));
            Double previousConfidence = number(merged.get("confidence"));
            String claim = firstNonEmpty(text(hypothesisUpdate.get("claim")), text(merged.get("claim")));
            String status = firstNonEmpty(text(hypothesisUpdate.get("status")), text(merged.get("status")));
            String updateReason = text(hypothesisUpdate.get("update_reason"));
            if (claim.isEmpty() || !HYPOTHESIS_STATUSES.contains(status) || updateReason.isEmpty()) {
                errors.add("假设 " + id + " 必须包含 claim、合法 status 和 update_reason");
                continue;
            }
            Object confidenceValue = hypothesisUpdate.containsKey("confidence")
                    ? hypothesisUpdate.get("confidence") : merged.get("confidence");
            Double confidence = number(confidenceValue);
            if (confidence == null || confidence < 0 || confidence > 1) {
                errors.add("假设 " + id + " 的 confidence 必须在 0 到 1 之间");
                continue;
            }
            List<String> supporting = normalizedEvidenceRefs(
                    hypothesisUpdate, merged, "supporting_evidence", usableSteps,
                    currentObservationSources, id, warnings);
            List<String> contradicting = normalizedEvidenceRefs(
                    hypothesisUpdate, merged, "contradicting_evidence", usableSteps,
                    currentObservationSources, id, warnings);
            boolean judgmentChanged = existingHypothesis
                    && (!status.equals(previousStatus)
                    || previousConfidence == null
                    || Double.compare(confidence, previousConfidence) != 0);
            if (judgmentChanged && supporting.isEmpty() && contradicting.isEmpty()) {
                warnings.add("假设 " + id + " 的判断变化缺少有效证据引用；已保留上一轮 status 和 confidence");
                status = previousStatus;
                confidence = previousConfidence;
            }
            merged.put("hypothesis_id", id);
            merged.put("claim", claim);
            merged.put("status", status);
            merged.put("confidence", confidence);
            merged.put("supporting_evidence", supporting);
            merged.put("contradicting_evidence", contradicting);
            merged.put("missing_evidence", stringListOrExisting(hypothesisUpdate, merged, "missing_evidence"));
            merged.put("last_update_reason", updateReason);
            byId.put(id, merged);
        }
        if (candidate.get("hypotheses") == null && byId.size() < 2) {
            errors.add("ReAct 首轮必须建立至少两个可区分的候选假设");
        }
        candidate.put("hypotheses", new ArrayList<>(byId.values()));
    }

    private void mergeDecisionState(
            Map<String, Object> candidate,
            Map<String, Object> update,
            Map<String, Object> response,
            RuntimeState state,
            boolean initialRound,
            List<String> errors) {
        List<String> contradictionCheck = json.asStringList(update.get("contradiction_check"));
        if (!initialRound && contradictionCheck.stream().allMatch(String::isBlank)) {
            errors.add("reasoning_update.contradiction_check 必须包含反证或尚未排除的替代解释");
        }

        List<Map<String, Object>> gaps = maps(update.get("ranked_information_gaps"));
        for (Map<String, Object> gap : gaps) {
            if (text(gap.get("gap")).isEmpty() || text(gap.get("reason")).isEmpty()
                    || priority(gap) == Integer.MAX_VALUE) {
                errors.add("ranked_information_gaps 每项必须包含 gap、数值 priority 和 reason");
            }
            if (!(gap.get("actionable") instanceof Boolean)) {
                errors.add("ranked_information_gaps 每项必须包含 boolean actionable");
            } else if (!json.asBoolean(gap.get("actionable"), true)
                    && text(gap.get("blocked_reason")).isEmpty()) {
                errors.add("actionable=false 的信息缺口必须填写 blocked_reason");
            }
        }
        gaps.sort(Comparator.comparingInt(this::priority));
        Map<String, Object> actionDecision = json.asMap(update.get("action_decision"));
        Map<String, Object> stopAssessment = json.asMap(update.get("stop_assessment"));
        Map<String, Object> selectedAction = json.asMap(response.get("selected_action"));
        String actionType = text(selectedAction.get("type"));
        boolean shouldStop = json.asBoolean(stopAssessment.get("should_stop"), false);
        String stopReason = text(stopAssessment.get("reason"));
        if (stopReason.isEmpty()) errors.add("stop_assessment.reason 不能为空");

        if ("tool_call".equals(actionType)) {
            String selectedGap = text(actionDecision.get("selected_gap"));
            String highestActionableGap = gaps.stream()
                    .filter(gap -> json.asBoolean(gap.get("actionable"), false))
                    .map(gap -> text(gap.get("gap")))
                    .findFirst()
                    .orElse("");
            if (selectedGap.isEmpty() || !selectedGap.equals(highestActionableGap)) {
                errors.add("调用工具时 selected_gap 必须等于最高优先级且 actionable=true 的信息缺口");
            }
            Map<String, Object> toolCall = json.asMap(selectedAction.get("tool_call"));
            String selectedTool = text(actionDecision.get("selected_tool"));
            String actualTool = text(toolCall.get("name"));
            if (selectedTool.isEmpty() || !selectedTool.equals(actualTool)) {
                errors.add("action_decision.selected_tool 必须与 selected_action.tool_call.name 完全一致");
            }
            validateToolFeasibility(state, actualTool, json.asMap(toolCall.get("input")), errors);
            if (text(actionDecision.get("why_now")).isEmpty()) {
                errors.add("调用工具时 action_decision.why_now 不能为空");
            }
            List<Map<String, Object>> branches = maps(actionDecision.get("expected_result_branches"));
            if (branches.size() < 2) {
                errors.add("调用工具前 expected_result_branches 至少需要两个可区分结果分支");
            }
            for (Map<String, Object> branch : branches) {
                if (text(branch.get("if")).isEmpty() || text(branch.get("then")).isEmpty()) {
                    errors.add("expected_result_branches 每个分支必须包含非空 if 和 then");
                }
            }
            if (shouldStop) errors.add("selected_action=tool_call 时 stop_assessment.should_stop 必须为 false");
        } else if ("final_answer".equals(actionType)) {
            if (!shouldStop) errors.add("selected_action=final_answer 时 stop_assessment.should_stop 必须为 true");
        } else {
            errors.add("selected_action.type 必须为 tool_call 或 final_answer");
        }

        candidate.put("open_questions", gaps);
        candidate.put("contradiction_check", contradictionCheck);
        candidate.put("last_action_decision", actionDecision);
        candidate.put("stop_assessment", stopAssessment);
    }

    private void validateToolFeasibility(
            RuntimeState state,
            String toolName,
            Map<String, Object> toolInput,
            List<String> errors) {
        if (toolName.isEmpty()) return;
        if (state.unavailableTools.contains(toolName)) {
            errors.add("selected_tool 当前依赖不可用，必须选择其他可执行缺口或结束: " + toolName);
            return;
        }
        String fingerprint = ToolFailureTracker.fingerprint(toolName, toolInput, json);
        if (state.blockedActionFingerprints.contains(fingerprint)) {
            errors.add("selected_tool 的相同 tool+input 已确认失败，禁止重复选择: " + toolName);
        }
    }

    private Set<String> usableEvidenceSteps(RuntimeState state) {
        Set<String> steps = new LinkedHashSet<>();
        for (AgentExecutionLogEntry entry : state.executionLog) {
            Map<String, Object> output = json.asMap(entry.getOutput());
            if ("success".equals(output.get("status"))
                    && json.asBoolean(output.get("validation_passed"), false)) {
                steps.add(entry.getStep());
            }
        }
        return steps;
    }

    private Set<String> validObservationSources(Map<String, Object> update, Set<String> usableSteps) {
        Set<String> sources = new LinkedHashSet<>();
        for (Map<String, Object> observation : maps(update.get("new_observations"))) {
            String source = text(observation.get("source_step"));
            if (usableSteps.contains(source)) sources.add(source);
        }
        return sources;
    }

    private List<String> normalizedEvidenceRefs(
            Map<String, Object> update,
            Map<String, Object> existing,
            String field,
            Set<String> usableSteps,
            Set<String> currentObservationSources,
            String hypothesisId,
            List<String> warnings) {
        if (!update.containsKey(field)) return json.asStringList(existing.get(field));
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawReference : json.asStringList(update.get(field))) {
            String reference = text(rawReference);
            if (usableSteps.contains(reference)) {
                normalized.add(reference);
                continue;
            }
            String embeddedStep = usableSteps.stream()
                    .filter(reference::contains)
                    .findFirst()
                    .orElse("");
            if (!embeddedStep.isEmpty()) {
                normalized.add(embeddedStep);
                warnings.add("假设 " + hypothesisId + " 的证据引用已从说明文字规范化为 " + embeddedStep);
                continue;
            }
            if (currentObservationSources.size() == 1) {
                String inferredStep = currentObservationSources.iterator().next();
                normalized.add(inferredStep);
                warnings.add("假设 " + hypothesisId + " 的证据引用已归一到本轮唯一观察来源 " + inferredStep);
                continue;
            }
            warnings.add("假设 " + hypothesisId + " 的无效证据引用已丢弃: " + reference);
        }
        return new ArrayList<>(normalized);
    }

    private List<String> stringListOrExisting(
            Map<String, Object> update, Map<String, Object> existing, String field) {
        return update.containsKey(field)
                ? json.asStringList(update.get(field))
                : json.asStringList(existing.get(field));
    }

    private List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = json.asMap(item);
                if (!map.isEmpty()) result.add(new LinkedHashMap<>(map));
            }
        }
        return result;
    }

    private Map<String, Object> deepCopy(Map<String, Object> value) {
        return json.asMap(json.toTree(value));
    }

    private int priority(Map<String, Object> gap) {
        Double value = number(gap.get("priority"));
        return value == null ? Integer.MAX_VALUE : value.intValue();
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> concat(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

    private String text(Object value) {
        return value instanceof String string ? string.trim() : "";
    }

    private String firstNonEmpty(String first, String second) {
        return first.isEmpty() ? second : first;
    }

    private UpdateResult invalid(String message) {
        return new UpdateResult(false, List.of(message), List.of());
    }

    public record UpdateResult(boolean valid, List<String> errors, List<String> warnings) {}
}
