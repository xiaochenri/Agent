package com.agent.javascope.context.projection;

import com.agent.javascope.context.budget.PromptBudget;

/** 将完整执行状态投影为适合本轮模型调用的工作上下文。 */
public interface ContextManager {

    /**
     * 按给定预算选择计划、历史、约束和证据，完整轨迹不会在此方法中被删除。
     *
     * @param request Core 提供的通用执行状态快照
     * @param budget 本轮 Prompt 的上下文选择预算
     * @return 可交给 Prompt 编排器的裁剪后上下文
     */
    WorkingContext project(ContextRequest request, PromptBudget budget);
}
