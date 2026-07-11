package com.agent.javascope.prompt;

import com.agent.javascope.contract.plan.FailedStepHistoryItem;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import java.util.List;
import java.util.Map;

public class LayeredAgentPromptProvider implements AgentPromptProvider {

    private final AgentPromptProvider baseProvider;
    private final List<AgentBusinessPromptCustomizer> customizers;

    public LayeredAgentPromptProvider(AgentPromptProvider baseProvider, List<AgentBusinessPromptCustomizer> customizers) {
        this.baseProvider = baseProvider;
        this.customizers = customizers;
    }

    @Override
    public String buildRoutePrompt(String systemPrompt, String input) {
        String prompt = baseProvider.buildRoutePrompt(systemPrompt, input);
        for (AgentBusinessPromptCustomizer customizer : customizers) {
            prompt = customizer.customizeRoutePrompt(prompt);
        }
        return prompt;
    }

    @Override
    public String buildDirectReplyPrompt(String systemPrompt, String input, String route, String routeReason) {
        String prompt = baseProvider.buildDirectReplyPrompt(systemPrompt, input, route, routeReason);
        for (AgentBusinessPromptCustomizer customizer : customizers) {
            prompt = customizer.customizeDirectReplyPrompt(prompt);
        }
        return prompt;
    }

    @Override
    public String buildActionPrompt(
            String systemPrompt,
            String input,
            String executionMode,
            String memoryJson,
            String toolsJson,
            String latestPlanJson,
            String executionLogJson,
            String validationFeedback) {
        String prompt = baseProvider.buildActionPrompt(
                systemPrompt, input, executionMode, memoryJson, toolsJson, latestPlanJson, executionLogJson, validationFeedback);
        for (AgentBusinessPromptCustomizer customizer : customizers) {
            prompt = customizer.customizeActionPrompt(prompt);
        }
        return prompt;
    }

    @Override
    public String buildPlanPrompt(String input, int round, String lastError, String toolsJson) {
        String prompt = baseProvider.buildPlanPrompt(input, round, lastError, toolsJson);
        for (AgentBusinessPromptCustomizer customizer : customizers) {
            prompt = customizer.customizePlanPrompt(prompt);
        }
        return prompt;
    }

    @Override
    public String buildRevisePlanPrompt(
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
            String toolsJson) {
        String prompt = baseProvider.buildRevisePlanPrompt(
                userInput,
                reason,
                currentPlan,
                failedStepIndex,
                failedStep,
                failureContext,
                failedSteps,
                completedStepFingerprints,
                failedStepFingerprints,
                failedStepHistory,
                round,
                lastError,
                toolsJson);
        for (AgentBusinessPromptCustomizer customizer : customizers) {
            prompt = customizer.customizePlanPrompt(prompt);
        }
        return prompt;
    }

    @Override
    public String buildValidationPrompt(String input, String planJson, String executionLogJson, String finalAnswerJson) {
        String prompt = baseProvider.buildValidationPrompt(input, planJson, executionLogJson, finalAnswerJson);
        for (AgentBusinessPromptCustomizer customizer : customizers) {
            prompt = customizer.customizeValidationPrompt(prompt);
        }
        return prompt;
    }

    @Override
    public String buildIndependentValidationPrompt(
            String taskJson, String acceptanceJson, String executionLogJson, String finalAnswerJson) {
        String prompt = baseProvider.buildIndependentValidationPrompt(
                taskJson, acceptanceJson, executionLogJson, finalAnswerJson);
        for (AgentBusinessPromptCustomizer customizer : customizers) {
            prompt = customizer.customizeIndependentValidationPrompt(prompt);
        }
        return prompt;
    }

    @Override
    public String buildClarificationInstruction(Map<String, Object> clarificationData, boolean retry) {
        String prompt = baseProvider.buildClarificationInstruction(clarificationData, retry);
        for (AgentBusinessPromptCustomizer customizer : customizers) {
            prompt = customizer.customizeClarificationPrompt(prompt);
        }
        return prompt;
    }
}
