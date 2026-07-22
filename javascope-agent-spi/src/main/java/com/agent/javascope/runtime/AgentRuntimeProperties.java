package com.agent.javascope.runtime;

public class AgentRuntimeProperties {

    private String provider = "deepseek";
    /**
     * 兼容旧版的全局模型配置。非空时优先于 provider 专属配置。
     */
    private String model = "";
    private String apiKey = "";
    private String baseUrl = "";
    private ProviderProperties deepseek = new ProviderProperties(
            "deepseek-chat", "https://api.deepseek.com/v1", true);
    private ProviderProperties kimi = new ProviderProperties(
            "kimi-k3", "https://api.moonshot.cn/v1", false);
    private String systemInstruction = "你是通用任务执行器，输出必须为 JSON。";
    private int planMaxRetry = 2;
    /** 幂等工具命中框架瞬时异常白名单后的自动重试次数。 */
    private int toolMaxRetries = 2;
    /** 一次 Agent 请求中全部工具共享的额外重试次数。 */
    private int toolRetryBudget = 6;
    private long toolRetryBaseDelayMs = 100;
    private long toolRetryMaxDelayMs = 3000;
    /** 包含模型与工具阶段在内的一次 Agent 请求截止时间。 */
    private long requestTimeoutMs = 3600000;
    private boolean finalAnswerValidationEnabled = true;
    private double temperature = 0.2;
    private int timeoutSeconds = 60;
    /** 可重试模型失败（429、5xx、网络异常）的额外尝试次数。 */
    private int modelMaxRetries = 2;
    private long modelRetryBaseDelayMs = 1000;
    private long modelRetryMaxDelayMs = 10000;
    private int contextMaxPromptCharacters = 120000;
    private int contextMaxHistoryItems = 6;
    private int contextMaxEvidenceItems = 8;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return firstNonBlank(model, activeProvider().getModel());
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return firstNonBlank(apiKey, activeProvider().getApiKey());
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return firstNonBlank(baseUrl, activeProvider().getBaseUrl());
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public ProviderProperties getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(ProviderProperties deepseek) {
        this.deepseek = deepseek == null ? new ProviderProperties() : deepseek;
    }

    public ProviderProperties getKimi() {
        return kimi;
    }

    public void setKimi(ProviderProperties kimi) {
        this.kimi = kimi == null ? new ProviderProperties() : kimi;
    }

    /** 是否向当前提供方发送 OpenAI temperature 参数。 */
    public boolean isTemperatureEnabled() {
        return activeProvider().isTemperatureEnabled();
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

    public int getModelMaxRetries() {
        return modelMaxRetries;
    }

    public void setModelMaxRetries(int modelMaxRetries) {
        this.modelMaxRetries = Math.max(0, modelMaxRetries);
    }

    public long getModelRetryBaseDelayMs() {
        return modelRetryBaseDelayMs;
    }

    public void setModelRetryBaseDelayMs(long modelRetryBaseDelayMs) {
        this.modelRetryBaseDelayMs = Math.max(0, modelRetryBaseDelayMs);
    }

    public long getModelRetryMaxDelayMs() {
        return modelRetryMaxDelayMs;
    }

    public void setModelRetryMaxDelayMs(long modelRetryMaxDelayMs) {
        this.modelRetryMaxDelayMs = Math.max(0, modelRetryMaxDelayMs);
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

    private ProviderProperties activeProvider() {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase();
        return switch (normalizedProvider) {
            case "deepseek" -> deepseek;
            case "kimi", "moonshot" -> kimi;
            default -> new ProviderProperties("", "", true);
        };
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** OpenAI-compatible 模型提供方的连接参数。 */
    public static class ProviderProperties {

        private String model = "";
        private String apiKey = "";
        private String baseUrl = "";
        private boolean temperatureEnabled = true;

        public ProviderProperties() {
        }

        public ProviderProperties(String model, String baseUrl, boolean temperatureEnabled) {
            this.model = model;
            this.baseUrl = baseUrl;
            this.temperatureEnabled = temperatureEnabled;
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

        public boolean isTemperatureEnabled() {
            return temperatureEnabled;
        }

        public void setTemperatureEnabled(boolean temperatureEnabled) {
            this.temperatureEnabled = temperatureEnabled;
        }
    }
}
