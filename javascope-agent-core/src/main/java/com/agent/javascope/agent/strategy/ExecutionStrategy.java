package com.agent.javascope.agent.strategy;

import com.agent.javascope.agent.runtime.RuntimeState;
import com.agent.javascope.entity.routing.RouteDecision;

/** 可替换的 Agent 执行策略边界。 */
public interface ExecutionStrategy {

    /** 初始化策略所需的模型、工具和运行时资源。 */
    void initialize();

    /** 判断策略是否处理该路由结果。 */
    boolean supports(RouteDecision routeDecision);

    /** 在统一创建的执行上下文中运行，并返回同一份运行时状态。 */
    RuntimeState execute(ExecutionRequest request);
}
