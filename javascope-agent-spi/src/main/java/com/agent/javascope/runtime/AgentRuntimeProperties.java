package com.agent.javascope.runtime;

public class AgentRuntimeProperties {

    private String provider = "openai";
    private String model = "";
    private String apiKey = "";
    private String baseUrl = "";
    private String systemInstruction = "你是通用任务执行器，输出必须为 JSON。";
    private int planMaxRetry = 2;
    /** 幂等工具命中框架瞬时异常白名单后的自动重试次数。 */
    private int toolMaxRetries = 2;
    /** 一次 Agent 请求中全部工具共享的额外重试次数。 */
    private int toolRetryBudget = 6;
    private long toolRetryBaseDelayMs = 100;
    private long toolRetryMaxDelayMs = 3000;
    /** 包含模型与工具阶段在内的一次 Agent 请求截止时间。 */
    private long requestTimeoutMs = 180000;
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

    public int getToolRetryBudget() {
        return toolRetryBudget;
    }

    public void setToolRetryBudget(int toolRetryBudget) {
        this.toolRetryBudget = Math.max(0, toolRetryBudget);
    }

    public long getToolRetryBaseDelayMs() {
        return toolRetryBaseDelayMs;
    }

    public void setToolRetryBaseDelayMs(long toolRetryBaseDelayMs) {
        this.toolRetryBaseDelayMs = Math.max(0, toolRetryBaseDelayMs);
    }

    public long getToolRetryMaxDelayMs() {
        return toolRetryMaxDelayMs;
    }

    public void setToolRetryMaxDelayMs(long toolRetryMaxDelayMs) {
        this.toolRetryMaxDelayMs = Math.max(0, toolRetryMaxDelayMs);
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = Math.max(1, requestTimeoutMs);
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
