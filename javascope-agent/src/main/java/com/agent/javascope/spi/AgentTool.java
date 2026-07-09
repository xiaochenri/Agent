package com.agent.javascope.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记一个 Spring Bean 方法为 Agent 可治理工具，并声明模型可见的工具协议。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

    /** 工具唯一名称，模型 tool_calls.name 和计划步骤 tool 都使用该值。 */
    String name();

    /** 面向人和模型的短标题；为空时默认使用 name。 */
    String title() default "";

    /** 工具能力、适用场景和边界说明。 */
    String description() default "";

    /** 业务或系统命名空间，例如 system.planning、finance.market。 */
    String namespace() default "";

    /** 工具分类，用于 prompt 分组、治理和观测。 */
    String category() default "";

    /** 工具契约版本，用于后续兼容性治理。 */
    String version() default "1.0.0";

    /** 轻量标签，例如 readonly、stock、planning。 */
    String[] tags() default {};

    /** 区分系统流程工具和业务执行工具。 */
    ToolType toolType() default ToolType.BUSINESS;

    /** 控制工具是否暴露给模型或仅允许 runtime 内部使用。 */
    ToolVisibility visibility() default ToolVisibility.MODEL_VISIBLE;

    /** 工具风险等级，供确认策略和安全策略使用。 */
    ToolDangerLevel dangerLevel() default ToolDangerLevel.SAFE;

    /** 是否只读；写操作或外部副作用工具应设置为 false。 */
    boolean readOnly() default true;

    /** 相同输入重复调用是否应产生等价结果或可安全重试。 */
    boolean idempotent() default true;

    /** 是否需要用户确认后才能执行。 */
    boolean requiresConfirmation() default false;

    /** 是否允许模型通过 tool_calls 直接调用。 */
    boolean allowedDirectCall() default true;

    /** 是否允许被写入计划步骤并由 PlanExecutor 执行。 */
    boolean allowedInPlanStep() default true;

    /** 工具建议超时时间，单位毫秒；当前主要用于协议暴露。 */
    int timeoutMs() default 30000;

    /** JSON Schema 字符串；为空时由方法入参尽力推断。 */
    String inputSchema() default "";

    /** JSON Schema 字符串；为空时使用统一 ToolResultPayload schema。 */
    String outputSchema() default "";

    /** 调用示例 JSON 字符串数组；解析失败时按纯文本描述保留。 */
    String[] examples() default {};
}
