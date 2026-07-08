package com.agent.javascope.verifier;

public class VerifierNextAction {

    private String category = "none";
    private String instruction = "";

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category == null ? "none" : category;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction == null ? "" : instruction;
    }
}
