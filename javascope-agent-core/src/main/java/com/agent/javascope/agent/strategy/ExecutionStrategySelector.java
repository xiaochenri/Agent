package com.agent.javascope.agent.strategy;

import com.agent.javascope.entity.routing.RouteDecision;

import java.util.List;
import java.util.Objects;

/** 管理默认执行策略；后续可按路由、租户或任务类型选择不同策略。 */
public class ExecutionStrategySelector {

    private final List<ExecutionStrategy> strategies;

    public ExecutionStrategySelector(List<ExecutionStrategy> strategies) {
        ExecutionStrategy first = strategies == null ? null : strategies.stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("at least one execution strategy is required"));
        this.strategies = strategies == null ? List.of(first) : List.copyOf(strategies);
    }

    /** 当前保持默认策略选择，避免改变既有执行行为。 */
    public ExecutionStrategy select(RouteDecision routeDecision) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(routeDecision))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no execution strategy supports route"));
    }
}
