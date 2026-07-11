package com.agent.javascope.agent;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import com.agent.javascope.contract.plan.FailedStepHistoryItem;
import com.agent.javascope.entity.plan.PlanRevisionRecord;
import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.agent.javascope.entity.plan.PlanStepState;
import com.agent.javascope.entity.plan.PlanToolData;
import com.agent.javascope.entity.routing.RouteDecision;
import com.agent.javascope.tools.validation.StepValidatorTool;
import com.agent.javascope.context.trace.ExecutionTrace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RuntimeState {
    final ExecutionTrace trace;

    RuntimeState(ExecutionTrace trace) {
        this.trace = trace;
    }

    // 全链路执行日志：记录每轮推理、工具调用与校验结果，最终原样返回给上层。
    final List<AgentExecutionLogEntry> executionLog = new ArrayList<>();
    // 计划修订轨迹：保存 create_plan/revise_plan 的多次尝试结果，便于追踪计划如何演进。
    final List<PlanRevisionRecord> revisedPlan = new ArrayList<>();
    // 计划生命周期事件：记录计划创建、步骤开始/完成/失败等状态流转，用于过程审计。
    final List<Map<String, Object>> planLifecycle = new ArrayList<>();
    // 轮间短期记忆：仅在下一次 reasoning 提示词中使用，调用后会清空。
    final List<String> ephemeralMemory = new ArrayList<>();
    // 当前可执行计划的结构化步骤列表，供 executePlan 顺序执行与依赖校验。
    final List<PlanStepState> planSteps = new ArrayList<>();
    // 最近一次计划工具返回的完整结果（含 task_understanding 等），用于最终响应聚合。
    PlanToolData latestPlanResult = new PlanToolData();
    // 最近生效的原始计划（Map 结构），用于构建步骤视图和后续推理提示。
    List<PlanStepDefinition> latestPlan = new ArrayList<>();
    // 风险标记集合：沉淀执行过程中的异常/阻塞信号，作为最终响应的风险输出。
    final List<String> riskFlags = new ArrayList<>();
    // 校验反馈文本：在结果验证失败后写入，下一轮作为修正提示引导模型重试。
    String validationFeedback = "";
    // 最近一次推理前的校验反馈，避免 reasoning 清空后 revise_plan 丢失失败原因。
    String lastValidationFeedback = "";
    // 最近一次最终答案校验结果：用于判断是否通过、是否建议重规划及失败原因。
    ValidationResult lastValidation = new ValidationResult(false, List.of("未开始验证"), false);
    // 最近一次模型给出的 final_answer，供验证与最终返回使用。
    Map<String, Object> lastFinalAnswer = new LinkedHashMap<>();
    // 前置路由结果：记录 chat/meta/task 分类结论，便于审计和后续分支控制。
    RouteDecision routeDecision = new RouteDecision();
    // 最近一次模型原始响应（含 tool_calls/final_answer），用于当前轮分支决策。
    Map<String, Object> lastResponse = new LinkedHashMap<>();
    // 最近一次步骤结果评估，供 revise_plan 使用失败上下文。
    StepValidatorTool.StepEvaluationResult lastStepEvaluation =
            StepValidatorTool.StepEvaluationResult.pass(1.0, new LinkedHashMap<>());
    // 最近一次执行失败时的计划指纹，避免 revise_plan 返回同构计划。
    String lastFailedPlanFingerprint = "";
    // 最近一次执行失败的步骤下标，用于只重规划当前失败步骤。
    int lastFailedStepIndex = -1;
    // 按计划链位置记录失败重试次数，超过 planMaxRetry 后放弃当前步骤并继续后续步骤。
    final Map<String, Integer> stepRetryCounts = new LinkedHashMap<>();
    // 已成功执行步骤缓存（tool+input 指纹 -> 输出），用于重规划后跳过重复步骤。
    final Map<String, Map<String, Object>> completedStepOutputs = new LinkedHashMap<>();
    // 历史失败步骤缓存（tool+input 指纹 -> 失败详情），用于 revise_plan 避免重复失败工具组合。
    final Map<String, FailedStepHistoryItem> failedStepHistory = new LinkedHashMap<>();
    // 计划版本号：每次重建计划自增，用于生成稳定且可追踪的 plan_id。
    int planVersion = 0;
    // 当前计划标识（如 plan_v1），用于串联步骤 ID 与生命周期日志。
    String currentPlanId = "";
    // 执行结束时的阻塞原因，由 execute 统一写入，交由回复层输出。
    String blockedReason = "";
    // 澄清阶段标记：已调用 clarify_requirement，下一轮必须由模型按固定格式输出澄清回复。
    boolean inClarificationStage = false;
    // 澄清工具返回的结构化上下文，供模型生成澄清回复时参考。
    Map<String, Object> clarificationData = new LinkedHashMap<>();
}
