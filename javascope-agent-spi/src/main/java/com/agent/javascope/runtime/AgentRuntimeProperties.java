package com.agent.javascope.runtime;

public class AgentRuntimeProperties {

    private String provider = "openai";
    private String model = "";
    private String apiKey = "";
    private String baseUrl = "";
    private String systemInstruction = "你是通用任务执行器，输出必须为 JSON。";
    /** 兼容旧配置；模式专属轮次未设置为正数时使用该值。 */
    private int maxRounds = 10;
    private int directMaxRounds = 2;
    private int plannedMaxRounds = 4;
    private int reactMaxRounds = 6;
    private int reactMaxToolCalls = 5;
    private int planMaxRetry = 2;
    /** 幂等工具返回 retryable=true 后的自动重试次数；默认关闭，由计划恢复统一决策。 */
    private int toolMaxRetries = 0;
    private boolean finalAnswerValidationEnabled = false;
    private double temperature = 0.2;
    private int timeoutSeconds = 60;
    private int contextMaxPromptCharacters = 2400000;
    private int contextMaxHistoryItems = 6;
    private int contextMaxEvidenceItems = 8;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSystemInstruction() {
        return systemInstruction;
    }

    public void setSystemInstruction(String systemInstruction) {
        this.systemInstruction = systemInstruction;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public int getDirectMaxRounds() {
        return directMaxRounds;
    }

    public void setDirectMaxRounds(int directMaxRounds) {
        this.directMaxRounds = directMaxRounds;
    }

    public int getPlannedMaxRounds() {
        return plannedMaxRounds;
    }

    public void setPlannedMaxRounds(int plannedMaxRounds) {
        this.plannedMaxRounds = plannedMaxRounds;
    }

    public int getReactMaxRounds() {
        return reactMaxRounds;
    }

    public void setReactMaxRounds(int reactMaxRounds) {
        this.reactMaxRounds = reactMaxRounds;
    }

    public int getReactMaxToolCalls() {
        return reactMaxToolCalls;
    }

    public void setReactMaxToolCalls(int reactMaxToolCalls) {
        this.reactMaxToolCalls = reactMaxToolCalls;
    }

    /** 根据执行模式解析决策轮次，非正的模式配置回退到旧版 maxRounds。 */
    public int resolveMaxRounds(String executionMode) {
        int configured = switch (executionMode == null ? "" : executionMode) {
            case "direct" -> directMaxRounds;
            case "planned" -> plannedMaxRounds;
            case "react" -> reactMaxRounds;
            default -> maxRounds;
        };
        return configured > 0 ? configured : Math.max(1, maxRounds);
    }

    public int getPlanMaxRetry() {
        return planMaxRetry;
    }

    public void setPlanMaxRetry(int planMaxRetry) {
        this.planMaxRetry = planMaxRetry;
    }

    public int getToolMaxRetries() {
        return toolMaxRetries;
    }

    public void setToolMaxRetries(int toolMaxRetries) {
        this.toolMaxRetries = Math.max(0, toolMaxRetries);
    }

    public boolean isFinalAnswerValidationEnabled() {
        return finalAnswerValidationEnabled;
    }

    public void setFinalAnswerValidationEnabled(boolean finalAnswerValidationEnabled) {
        this.finalAnswerValidationEnabled = finalAnswerValidationEnabled;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getContextMaxPromptCharacters() {
        return contextMaxPromptCharacters;
    }

    public void setContextMaxPromptCharacters(int contextMaxPromptCharacters) {
        this.contextMaxPromptCharacters = contextMaxPromptCharacters;
    }

    public int getContextMaxHistoryItems() {
        return contextMaxHistoryItems;
    }

    public void setContextMaxHistoryItems(int contextMaxHistoryItems) {
        this.contextMaxHistoryItems = contextMaxHistoryItems;
    }

    public int getContextMaxEvidenceItems() {
        return contextMaxEvidenceItems;
    }

    public void setContextMaxEvidenceItems(int contextMaxEvidenceItems) {
        this.contextMaxEvidenceItems = contextMaxEvidenceItems;
    }
}
