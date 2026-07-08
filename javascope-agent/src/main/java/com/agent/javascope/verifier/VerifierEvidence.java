package com.agent.javascope.verifier;

public class VerifierEvidence {

    private String type = "manual_note";
    private String ref = "";

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type == null ? "manual_note" : type;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref == null ? "" : ref;
    }
}
