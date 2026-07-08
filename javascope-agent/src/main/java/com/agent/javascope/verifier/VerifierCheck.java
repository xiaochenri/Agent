package com.agent.javascope.verifier;

public class VerifierCheck {

    private String id = "";
    private String name = "";
    private String level = "blocking";
    private String result = "fail";
    private String reason = "";

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level == null ? "blocking" : level;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result == null ? "fail" : result;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason == null ? "" : reason;
    }
}
