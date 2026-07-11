package com.agent.javascope.entity.response;

import java.util.ArrayList;
import java.util.List;

/**
 * 面向业务集成的结构化结果区。
 */
public class AgentResponseData {

    /** 文件、图表、表格等可扩展产物。 */
    private List<Object> artifacts = new ArrayList<>();
    /** 引用、证据来源或外部链接。 */
    private List<Object> citations = new ArrayList<>();

    public List<Object> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(List<Object> artifacts) {
        this.artifacts = artifacts == null ? new ArrayList<>() : artifacts;
    }

    public List<Object> getCitations() {
        return citations;
    }

    public void setCitations(List<Object> citations) {
        this.citations = citations == null ? new ArrayList<>() : citations;
    }
}
