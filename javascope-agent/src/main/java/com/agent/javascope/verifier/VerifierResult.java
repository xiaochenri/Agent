package com.agent.javascope.verifier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VerifierResult {

    private String verdict = "fail";
    private String summary = "";
    private List<VerifierCheck> checks = new ArrayList<>();
    private List<VerifierEvidence> evidence = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    @JsonProperty("next_action")
    private VerifierNextAction nextAction = new VerifierNextAction();

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict == null ? "fail" : verdict;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    public List<VerifierCheck> getChecks() {
        return checks == null ? new ArrayList<>() : checks;
    }

    public void setChecks(List<VerifierCheck> checks) {
        this.checks = checks == null ? new ArrayList<>() : checks;
    }

    public List<VerifierEvidence> getEvidence() {
        return evidence == null ? new ArrayList<>() : evidence;
    }

    public void setEvidence(List<VerifierEvidence> evidence) {
        this.evidence = evidence == null ? new ArrayList<>() : evidence;
    }

    public List<String> getWarnings() {
        return warnings == null ? new ArrayList<>() : warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }

    public VerifierNextAction getNextAction() {
        return nextAction == null ? new VerifierNextAction() : nextAction;
    }

    @JsonProperty("next_action")
    public void setNextAction(VerifierNextAction nextAction) {
        this.nextAction = nextAction == null ? new VerifierNextAction() : nextAction;
    }
}
