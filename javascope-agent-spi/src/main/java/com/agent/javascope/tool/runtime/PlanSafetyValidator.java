package com.agent.javascope.tool.runtime;

import com.agent.javascope.contract.plan.PlanStepDefinition;
import java.util.List;

/**
 * 业务计划安全校验扩展点。
 *
 * <p>通用 Core 无法理解股票代码、订单号等领域关键参数，因此由业务模块校验计划里的
 * P0 参数是否来自用户输入或可信上下文，防止规划模型凭空补出执行对象。</p>
 */
public interface PlanSafetyValidator {

    /** 返回计划安全错误；空列表表示允许执行。 */
    default List<String> validate(String userInput, List<PlanStepDefinition> plan) {
        return List.of();
    }
}
