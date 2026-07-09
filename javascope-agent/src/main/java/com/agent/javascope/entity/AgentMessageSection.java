package com.agent.javascope.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户可见回复中的一个结构化段落。
 */
public class AgentMessageSection {

    /** 段落类型，例如 conclusion、known_info、risk、next_actions。 */
    private String type = "";
    /** 段落标题，供前端渲染使用。 */
    private String title = "";
    /** 段落条目；前端可渲染为列表。 */
    private List<String> items = new ArrayList<>();

    public AgentMessageSection() {}

    public AgentMessageSection(String type, String title, List<String> items) {
        this.type = type == null ? "" : type;
        this.title = title == null ? "" : title;
        this.items = items == null ? new ArrayList<>() : items;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type == null ? "" : type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public List<String> getItems() {
        return items;
    }

    public void setItems(List<String> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }
}
