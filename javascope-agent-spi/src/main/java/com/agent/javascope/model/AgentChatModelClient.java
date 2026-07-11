package com.agent.javascope.model;

import java.util.function.Consumer;

/**
 * Agent Runtime 访问大模型的通用抽象。
 *
 * <p>框架内的 Agent、规划工具、校验器只依赖该接口；具体项目可以替换为 OpenAI-compatible、
 * 本地模型、网关代理或测试桩实现。</p>
 */
public interface AgentChatModelClient {

    /**
     * 发起一次非流式模型调用。
     *
     * @param prompt runtime 已经组装好的完整用户 prompt
     * @return 模型返回的文本内容，当前 runtime 期望内容通常是 JSON 字符串
     */
    ModelResult chat(ModelRequest request);

    /**
     * 发起一次流式模型调用。
     *
     * @param prompt runtime 已经组装好的完整用户 prompt
     * @param deltaConsumer 增量文本回调；允许为 null
     * @return 聚合后的完整模型文本内容，当前 runtime 期望内容通常是 JSON 字符串
     */
    ModelResult chatStream(ModelRequest request, Consumer<String> deltaConsumer);
}
