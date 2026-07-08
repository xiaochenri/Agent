package com.agent.javascope.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

public class ToolResultPayload {

    /** 产生该结果的工具名称。 */
    private String tool = "";
    /** 工具执行状态，当前约定为 success 或 failed。 */
    private String status = "";
    /** 工具结果是否通过本地结构校验。 */
    @JsonProperty("validation_passed")
    private boolean validationPassed;
    /** 工具结果结构校验规则列表。 */
    @JsonProperty("validation_rules")
    private List<String> validationRules = new ArrayList<>();
    /** 工具结果结构校验失败时的错误列表。 */
    @JsonProperty("validation_errors")
    private List<String> validationErrors = new ArrayList<>();
    /** 工具失败时是否可重试。 */
    private boolean retryable;
    /** 工具业务数据。不同工具返回结构不同，因此保留为对象。 */
    private Object data;

    public ToolResultPayload() {}

    public ToolResultPayload(
            String tool,
            String status,
            boolean validationPassed,
            List<String> validationRules,
            List<String> validationErrors,
            boolean retryable,
            Object data) {
        this.tool = tool;
        this.status = status;
        this.validationPassed = validationPassed;
        this.validationRules = validationRules == null ? new ArrayList<>() : validationRules;
        this.validationErrors = validationErrors == null ? new ArrayList<>() : validationErrors;
        this.retryable = retryable;
        this.data = data;
    }

    public static ToolResultPayload success(String tool, Object data) {
        return new ToolResultPayload(tool, "success", true, List.of(), List.of(), false, data);
    }

    public static ToolResultPayload failed(String tool, List<String> validationErrors, Object data) {
        return new ToolResultPayload(tool, "failed", false, List.of(), validationErrors, false, data);
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool == null ? "" : tool;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    public boolean isValidationPassed() {
        return validationPassed;
    }

    public void setValidationPassed(boolean validationPassed) {
        this.validationPassed = validationPassed;
    }

    public List<String> getValidationErrors() {
        return validationErrors == null ? new ArrayList<>() : validationErrors;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public List<String> getValidationRules() {
        return validationRules == null ? new ArrayList<>() : validationRules;
    }

    public void setValidationRules(List<String> validationRules) {
        this.validationRules = validationRules == null ? new ArrayList<>() : validationRules;
    }

    public void setValidationErrors(List<String> validationErrors) {
        this.validationErrors = validationErrors == null ? new ArrayList<>() : validationErrors;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
