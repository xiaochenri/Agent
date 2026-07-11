package com.agent.javascope.prompt;

import com.agent.javascope.contract.plan.FailedStepHistoryItem;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import java.util.List;
import java.util.Map;

public interface AgentPromptProvider {

    String buildRoutePrompt(String systemPrompt, String input);

    String buildDirectReplyPrompt(String systemPrompt, String input, String route, String routeReason);

    String buildActionPrompt(
            String systemPrompt,
            String input,
            String executionMode,
            String memoryJson,
            String toolsJson,
            String latestPlanJson,
            String executionLogJson,
            String validationFeedback);

    String buildPlanPrompt(String input, int round, String lastError, String toolsJson);

    String buildRevisePlanPrompt(
            String userInput,
            String reason,
            List<PlanStepDefinition> currentPlan,
            int failedStepIndex,
            PlanStepDefinition failedStep,
            Map<String, Object> failureContext,
            List<Map<String, Object>> failedSteps,
            List<String> completedStepFingerprints,
            List<String> failedStepFingerprints,
            List<FailedStepHistoryItem> failedStepHistory,
            int round,
            String lastError,
            String toolsJson);

    String buildValidationPrompt(String input, String planJson, String executionLogJson, String finalAnswerJson);

    String buildIndependentValidationPrompt(
            String taskJson, String acceptanceJson, String executionLogJson, String finalAnswerJson);

    String buildClarificationInstruction(Map<String, Object> clarificationData, boolean retry);
}
