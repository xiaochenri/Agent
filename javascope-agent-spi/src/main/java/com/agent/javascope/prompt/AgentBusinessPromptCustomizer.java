package com.agent.javascope.prompt;

public interface AgentBusinessPromptCustomizer {

    default String customizeRoutePrompt(String basePrompt) {
        return basePrompt;
    }

    default String customizeDirectReplyPrompt(String basePrompt) {
        return basePrompt;
    }

    default String customizeActionPrompt(String basePrompt) {
        return basePrompt;
    }

    default String customizePlanPrompt(String basePrompt) {
        return basePrompt;
    }

    default String customizeValidationPrompt(String basePrompt) {
        return basePrompt;
    }

    default String customizeIndependentValidationPrompt(String basePrompt) {
        return basePrompt;
    }

    default String customizeClarificationPrompt(String basePrompt) {
        return basePrompt;
    }
}
