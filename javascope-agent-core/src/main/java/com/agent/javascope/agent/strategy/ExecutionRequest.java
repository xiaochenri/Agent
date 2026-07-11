package com.agent.javascope.agent.strategy;

import com.agent.javascope.agent.runtime.RuntimeState;

import java.util.Map;
import java.util.function.Consumer;

/** 由运行时门面创建并交给执行策略的统一请求上下文。 */
public record ExecutionRequest(
        String input,
        RuntimeState state,
        Consumer<Map<String, Object>> eventConsumer,
        boolean useModelStream) {}
