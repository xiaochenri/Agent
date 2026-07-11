package com.agent.javascope.tools.validation;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.validation.StepFailureCode;
import com.agent.javascope.entity.plan.PlanStepState;
import com.agent.javascope.json.AgentJsonCodecUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StepValidatorTool {

    public static final String TOOL_NAME = "step_validator";

    private final AgentJsonCodecUtil json;

    public StepValidatorTool(AgentJsonCodecUtil json) {
        this.json = json;
    }

    public StepEvaluationResult evaluateStepOutcome(PlanStepState step, Map<String, Object> resultJson) {
        List<String> hardFailures = new ArrayList<>();
        List<String> softFailures = new ArrayList<>();
        String expectedOutcome = step.getExpectedOutcome();
        Map<String, Object> data = json.asMap(resultJson.get("data"));

        if (!isToolSuccess(resultJson)) {
            hardFailures.add("tool_status_not_success");
            return StepEvaluationResult.fail(StepFailureCode.TOOL_EXECUTION_FAILED, hardFailures, softFailures, 0.0, resultJson);
        }

        for (String field : extractExpectedFieldCandidates(expectedOutcome, data)) {
            Object value = data.get(field);
            if (value == null || (value instanceof String text && text.isBlank())) {
                softFailures.add("missing_expected_field_" + field);
            }
        }

        for (Map.Entry<String, String> entry : extractExpectedKeyValueConstraints(expectedOutcome).entrySet()) {
            String key = entry.getKey();
            if (!data.containsKey(key)) {
                continue;
            }
            String expectedValue = entry.getValue();
            String actualValue = String.valueOf(data.get(key));
            if (!expectedValue.equals(actualValue)) {
                softFailures.add("constraint_mismatch_" + key + "_expected_" + expectedValue + "_actual_" + actualValue);
            }
        }
        for (String literal : extractExpectedLiterals(expectedOutcome)) {
            if (isStrictIdentifierLiteral(literal) && !hasExactScalarValue(data, literal)) {
                softFailures.add("expected_literal_not_matched_" + literal);
            }
        }

        if (!step.getInput().isEmpty()) {
            int consistentCount = 0;
            int comparableCount = 0;
            for (Map.Entry<String, Object> entry : step.getInput().entrySet()) {
                Object expectedValue = entry.getValue();
                if (!(expectedValue instanceof String || expectedValue instanceof Number || expectedValue instanceof Boolean)) {
                    continue;
                }
                Object actualValue = data.get(entry.getKey());
                if (actualValue == null) {
                    continue;
                }
                comparableCount++;
                if (Objects.equals(String.valueOf(expectedValue), String.valueOf(actualValue))) {
                    consistentCount++;
                }
            }
            if (comparableCount > 0 && consistentCount < comparableCount) {
                softFailures.add("input_output_inconsistent");
            }
        }

        double score = softFailures.isEmpty() ? 1.0 : 0.7;
        if (score < 0.8) {
            return StepEvaluationResult.fail(StepFailureCode.SEMANTIC_MISMATCH, hardFailures, softFailures, score, resultJson);
        }
        return StepEvaluationResult.pass(score, resultJson);
    }

    public StepEvaluationResult buildToolExecutionFailedResult(Map<String, Object> resultJson) {
        return StepEvaluationResult.fail(
                StepFailureCode.TOOL_EXECUTION_FAILED, List.of("tool_status_not_success"), List.of(), 0.0, resultJson);
    }

    public StepEvaluationResult buildPassResult(double score, Map<String, Object> evidence) {
        return StepEvaluationResult.pass(score, evidence);
    }

    public AgentExecutionLogEntry buildStepValidationLog(int round, PlanStepState step, StepEvaluationResult evaluation) {
        return new AgentExecutionLogEntry(
                "step_validation_round_" + round,
                TOOL_NAME,
                Map.of(
                        "step_id", step.getStepId(),
                        "step_name", step.getName(),
                        "expected_outcome", step.getExpectedOutcome()),
                evaluation.toMap(),
                evaluation.passed() ? 0.9 : 0.3);
    }

    private boolean isToolSuccess(Map<String, Object> resultJson) {
        return "success".equals(json.normalize((String) resultJson.get("status"), ""));
    }

    private Set<String> extractExpectedFieldCandidates(String expectedOutcome, Map<String, Object> data) {
        if (expectedOutcome == null || expectedOutcome.isBlank()) {
            return Set.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        Set<String> dataKeys = data.keySet();
        Matcher wordMatcher = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b").matcher(expectedOutcome);
        while (wordMatcher.find()) {
            String token = wordMatcher.group();
            for (String key : dataKeys) {
                if (token.equalsIgnoreCase(key)) {
                    fields.add(key);
                }
            }
        }
        return fields;
    }

    private Map<String, String> extractExpectedKeyValueConstraints(String expectedOutcome) {
        Map<String, String> constraints = new LinkedHashMap<>();
        if (expectedOutcome == null || expectedOutcome.isBlank()) {
            return constraints;
        }
        Matcher matcher = Pattern.compile(
                        "([A-Za-z_][A-Za-z0-9_]*)\\s*(?:为|=|:)\\s*['\"]?([A-Za-z0-9_\\-\\.]+)['\"]?",
                        Pattern.CASE_INSENSITIVE)
                .matcher(expectedOutcome);
        while (matcher.find()) {
            constraints.put(matcher.group(1), matcher.group(2));
        }
        return constraints;
    }

    private Set<String> extractExpectedLiterals(String expectedOutcome) {
        if (expectedOutcome == null || expectedOutcome.isBlank()) {
            return Set.of();
        }
        Set<String> literals = new LinkedHashSet<>();
        Matcher quotedMatcher = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(expectedOutcome);
        while (quotedMatcher.find()) {
            String literal = quotedMatcher.group(1).trim();
            if (!literal.isEmpty()) {
                literals.add(literal);
            }
        }
        Matcher tokenMatcher = Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z0-9_\\-\\.]{4,})(?![A-Za-z0-9_])")
                .matcher(expectedOutcome);
        while (tokenMatcher.find()) {
            literals.add(tokenMatcher.group(1));
        }
        return literals;
    }

    private boolean isStrictIdentifierLiteral(String literal) {
        return Pattern.compile("^\\d{4,}$").matcher(literal).matches()
                || Pattern.compile("^[A-Za-z]{1,8}\\d{2,}$").matcher(literal).matches();
    }

    private boolean hasExactScalarValue(Map<String, Object> data, String expectedLiteral) {
        for (Object value : data.values()) {
            if (value == null) {
                continue;
            }
            if (value instanceof String text && expectedLiteral.equalsIgnoreCase(text.trim())) {
                return true;
            }
            if ((value instanceof Number || value instanceof Boolean)
                    && expectedLiteral.equals(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    public record StepEvaluationResult(
            boolean passed,
            StepFailureCode failureCode,
            List<String> reasons,
            double score,
            Map<String, Object> evidence) {

        public static StepEvaluationResult pass(double score, Map<String, Object> evidence) {
            return new StepEvaluationResult(true, StepFailureCode.NONE, List.of(), score, evidence);
        }

        public static StepEvaluationResult fail(
                StepFailureCode failureCode,
                List<String> hardFailures,
                List<String> softFailures,
                double score,
                Map<String, Object> evidence) {
            List<String> mergedReasons = new ArrayList<>(hardFailures);
            mergedReasons.addAll(softFailures);
            return new StepEvaluationResult(false, failureCode, mergedReasons, score, evidence);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("passed", passed);
            map.put("failure_code", failureCode.code());
            map.put("failure_code_desc", failureCode.descriptionZh());
            map.put("reasons", reasons);
            map.put("score", score);
            map.put("evidence", evidence);
            return map;
        }
    }
}
