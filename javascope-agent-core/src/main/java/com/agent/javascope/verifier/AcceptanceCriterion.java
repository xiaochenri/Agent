package com.agent.javascope.verifier;

public class AcceptanceCriterion {

    private String id = "";
    private String name = "";
    private String level = "blocking";

    public AcceptanceCriterion() {}

    public AcceptanceCriterion(String id, String name, String level) {
        this.id = id;
        this.name = name;
        this.level = level;
    }

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
}
