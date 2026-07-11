package com.agent.javascope.entity.plan;

import com.agent.javascope.entity.tool.ToolResultPayload;
public class PlanRevisionRecord {

    /** revise_plan 的输入快照。 */
    private RevisePlanRequest input = new RevisePlanRequest();
    /** revise_plan 的输出快照。 */
    private ToolResultPayload output = new ToolResultPayload();

    public PlanRevisionRecord() {}

    public PlanRevisionRecord(RevisePlanRequest input, ToolResultPayload output) {
        this.input = input == null ? new RevisePlanRequest() : input;
        this.output = output == null ? new ToolResultPayload() : output;
    }

    public RevisePlanRequest getInput() {
        return input;
    }

    public void setInput(RevisePlanRequest input) {
        this.input = input == null ? new RevisePlanRequest() : input;
    }

    public ToolResultPayload getOutput() {
        return output;
    }

    public void setOutput(ToolResultPayload output) {
        this.output = output == null ? new ToolResultPayload() : output;
    }
}
