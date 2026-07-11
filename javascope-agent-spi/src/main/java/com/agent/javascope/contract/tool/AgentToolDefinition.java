package com.agent.javascope.contract.tool;

import com.agent.javascope.tool.annotation.ToolDangerLevel;
import com.agent.javascope.tool.annotation.ToolType;
import com.agent.javascope.tool.annotation.ToolVisibility;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具的标准协议定义。
 *
 * <p>该对象由 @AgentTool 元数据和方法签名生成，并作为 prompt 中的工具清单、执行前校验、
 * 计划步骤治理和后续观测审计的统一数据源。</p>
 */
public class AgentToolDefinition {

    /** 工具唯一名称，必须与 @AgentTool.name 以及模型 tool_calls.name 一致。 */
    private String name = "";
    /** 短标题，便于 UI 展示和模型快速识别工具用途。 */
    private String title = "";
    /** 工具能力、适用场景、输入要求和边界说明。 */
    private String description = "";
    /** 命名空间，用于区分系统工具和具体业务域，例如 system.planning、finance.market。 */
    private String namespace = "";
    /** 细分类别，用于 prompt 分组、治理策略和指标统计。 */
    private String category = "";
    /** 工具契约版本，用于后续兼容性和灰度治理。 */
    private String version = "1.0.0";
    /** 标签集合，用于轻量检索、过滤和策略匹配。 */
    private List<String> tags = new ArrayList<>();
    /** 工具语义类型：系统流程工具或业务执行工具。 */
    @JsonProperty("tool_type")
    private ToolType toolType = ToolType.BUSINESS;
    /** 工具可见性，决定是否进入模型可用工具列表。 */
    private ToolVisibility visibility = ToolVisibility.MODEL_VISIBLE;
    /** 风险等级，供自动调用、确认和拦截策略使用。 */
    @JsonProperty("danger_level")
    private ToolDangerLevel dangerLevel = ToolDangerLevel.SAFE;
    /** 是否只读；写入、下单、删除等有副作用工具应为 false。 */
    @JsonProperty("read_only")
    private boolean readOnly = true;
    /** 是否幂等；非幂等工具重复调用可能产生额外副作用。 */
    private boolean idempotent = true;
    /** 是否需要用户确认后执行。 */
    @JsonProperty("requires_confirmation")
    private boolean requiresConfirmation;
    /** 是否允许模型在 tool_calls 中直接调用。 */
    @JsonProperty("allowed_direct_call")
    private boolean allowedDirectCall = true;
    /** 是否允许出现在计划步骤中并由 PlanExecutor 执行。 */
    @JsonProperty("allowed_in_plan_step")
    private boolean allowedInPlanStep = true;
    /** 建议超时时间，单位毫秒；目前作为协议字段暴露。 */
    @JsonProperty("timeout_ms")
    private int timeoutMs = 30000;
    /** JSON Schema 格式的工具入参定义。 */
    @JsonProperty("input_schema")
    private Map<String, Object> inputSchema = new LinkedHashMap<>();
    /** JSON Schema 格式的工具返回定义。 */
    @JsonProperty("output_schema")
    private Map<String, Object> outputSchema = new LinkedHashMap<>();
    /** 工具调用示例，供 prompt、文档或 UI 使用。 */
    private List<Map<String, Object>> examples = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace == null ? "" : namespace;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category == null ? "" : category;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version == null ? "1.0.0" : version;
    }

    public List<String> getTags() {
        return tags == null ? new ArrayList<>() : tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }

    public ToolType getToolType() {
        return toolType;
    }

    public void setToolType(ToolType toolType) {
        this.toolType = toolType == null ? ToolType.BUSINESS : toolType;
    }

    public ToolVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(ToolVisibility visibility) {
        this.visibility = visibility == null ? ToolVisibility.MODEL_VISIBLE : visibility;
    }

    public ToolDangerLevel getDangerLevel() {
        return dangerLevel;
    }

    public void setDangerLevel(ToolDangerLevel dangerLevel) {
        this.dangerLevel = dangerLevel == null ? ToolDangerLevel.SAFE : dangerLevel;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public void setIdempotent(boolean idempotent) {
        this.idempotent = idempotent;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public boolean isAllowedDirectCall() {
        return allowedDirectCall;
    }

    public void setAllowedDirectCall(boolean allowedDirectCall) {
        this.allowedDirectCall = allowedDirectCall;
    }

    public boolean isAllowedInPlanStep() {
        return allowedInPlanStep;
    }

    public void setAllowedInPlanStep(boolean allowedInPlanStep) {
        this.allowedInPlanStep = allowedInPlanStep;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema == null ? new LinkedHashMap<>() : inputSchema;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema == null ? new LinkedHashMap<>() : inputSchema;
    }

    public Map<String, Object> getOutputSchema() {
        return outputSchema == null ? new LinkedHashMap<>() : outputSchema;
    }

    public void setOutputSchema(Map<String, Object> outputSchema) {
        this.outputSchema = outputSchema == null ? new LinkedHashMap<>() : outputSchema;
    }

    public List<Map<String, Object>> getExamples() {
        return examples == null ? new ArrayList<>() : examples;
    }

    public void setExamples(List<Map<String, Object>> examples) {
        this.examples = examples == null ? new ArrayList<>() : examples;
    }
}
