package com.agent.javascope.contract.plan;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 计划步骤的机器可校验输出约束。
 * 自然语言 expected_outcome 负责说明目标，本结构负责判断目标是否真的达成。
 */
public class PlanOutputRequirement {

    /** 相对于工具完整返回值的 JSON 路径，例如 data.net_profit。 */
    private String path = "";
    /** 期望类型：string、number、boolean、object、array 或 any。 */
    private String type = "any";
    /** 是否允许字段存在但值为 null，默认不允许。 */
    private boolean nullable;
    /** 可选的固定期望值；为空时只校验存在性和类型。 */
    @JsonProperty("expected_value")
    private Object expectedValue;

    public PlanOutputRequirement() {}

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path == null ? "" : path;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type == null ? "any" : type;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public Object getExpectedValue() {
        return expectedValue;
    }

    public void setExpectedValue(Object expectedValue) {
        this.expectedValue = expectedValue;
    }
}
