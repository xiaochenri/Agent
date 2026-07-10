package com.agent.javascope.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 对用户展示的干净回复。
 */
public class AgentUserMessage {

    /** 内容格式，当前默认 markdown。 */
    private String format = "markdown";
    /** 可直接渲染给用户的文本。 */
    private String content = "";
    /** 结构化段落，供前端做更精细渲染。 */
    private List<AgentMessageSection> sections = new ArrayList<>();
    /** 用户可以继续点击或复用的建议操作。 */
    @JsonProperty("next_actions")
    private List<AgentNextAction> nextActions = new ArrayList<>();

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format == null || format.isBlank() ? "markdown" : format;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }

    public List<AgentMessageSection> getSections() {
        return sections;
    }

    public void setSections(List<AgentMessageSection> sections) {
        this.sections = sections == null ? new ArrayList<>() : sections;
    }

    public List<AgentNextAction> getNextActions() {
        return nextActions;
    }

    public void setNextActions(List<AgentNextAction> nextActions) {
        this.nextActions = nextActions == null ? new ArrayList<>() : nextActions;
    }
}
