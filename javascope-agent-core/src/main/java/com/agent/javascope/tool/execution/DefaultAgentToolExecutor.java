package com.agent.javascope.tool.execution;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.tool.authorization.ToolAuthorizationDecision;
import com.agent.javascope.tool.authorization.ToolAuthorizationPolicy;
import com.agent.javascope.tool.contract.JsonSchemaToolContractValidator;
import com.agent.javascope.tool.contract.ToolContractValidator;
import com.agent.javascope.tool.contract.ToolSemanticValidator;
import com.agent.javascope.tool.invocation.ToolInvoker;
import com.agent.javascope.tool.error.DefaultToolErrorClassifier;
import com.agent.javascope.tool.middleware.ToolExecutionContext;
import com.agent.javascope.tool.middleware.ToolInvocationChain;
import com.agent.javascope.tool.middleware.ToolMiddleware;
import com.agent.javascope.tool.middleware.ToolResultFactory;
import com.agent.javascope.tool.registry.ToolRegistry;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolExecutionStatus;
import com.agent.javascope.tool.runtime.ToolErrorCode;
import com.agent.javascope.tool.runtime.ToolInvocation;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 统一串联工具注册、授权、治理链和实际执行的门面。 */
public class DefaultAgentToolExecutor implements AgentToolExecutor {

    private static final System.Logger LOG = System.getLogger(DefaultAgentToolExecutor.class.getName());

    private final ToolRegistry registry;
    private final ToolAuthorizationPolicy authorizationPolicy;
    private final List<ToolMiddleware> middlewares;
    private final ToolInvoker invoker;
    private final AgentJsonCodecUtil json;
    private final ToolContractValidator contractValidator;
    private final List<ToolSemanticValidator> semanticValidators;

    public DefaultAgentToolExecutor(
            ToolRegistry registry,
            ToolAuthorizationPolicy authorizationPolicy,
            List<ToolMiddleware> middlewares,
            ToolInvoker invoker,
            AgentJsonCodecUtil json) {
        this(registry, authorizationPolicy, middlewares, invoker, json,
                new JsonSchemaToolContractValidator(), List.of());
    }

    public DefaultAgentToolExecutor(
            ToolRegistry registry,
            ToolAuthorizationPolicy authorizationPolicy,
            List<ToolMiddleware> middlewares,
            ToolInvoker invoker,
            AgentJsonCodecUtil json,
            ToolContractValidator contractValidator,
            List<ToolSemanticValidator> semanticValidators) {
        this.registry = registry;
        this.authorizationPolicy = authorizationPolicy;
        this.middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
        this.invoker = invoker;
        this.json = json;
        this.contractValidator = contractValidator == null
                ? new JsonSchemaToolContractValidator()
                : contractValidator;
        this.semanticValidators = semanticValidators == null ? List.of() : List.copyOf(semanticValidators);
    }

    @Override
    public ToolExecutionResult execute(ToolInvocation invocation) {
        String toolName = invocation == null ? "" : invocation.toolName();
        try {
            return executeInternal(invocation, toolName);
        } catch (Exception error) {
            LOG.log(System.Logger.Level.WARNING,
                    "Tool execution failed, tool=" + toolName + ", exceptionType=" + error.getClass().getName(),
                    error);
            return ToolResultFactory.failed(
                    toolName, ToolErrorCode.TOOL_INTERNAL_ERROR, "工具运行时处理失败", false);
        }
    }

    private ToolExecutionResult executeInternal(ToolInvocation invocation, String toolName) {
        AgentToolDefinition definition = registry.findDefinition(toolName);
        if (definition == null) {
            return failure(toolName, "工具未注册", ToolErrorCode.TOOL_NOT_REGISTERED, false);
        }
        // 输入结构和业务语义在授权及业务方法执行之前确定性校验，模型不能绕过工具契约。
        List<String> inputErrors = contractValidator.validateInput(definition, invocation.input());
        for (ToolSemanticValidator validator : supportedSemanticValidators(toolName)) {
            inputErrors = merge(inputErrors, validator.validateInput(definition, invocation.input()));
        }
        if (!inputErrors.isEmpty()) {
            return failure(toolName, String.join("; ", inputErrors),
                    ToolErrorCode.TOOL_INPUT_CONTRACT_VIOLATION, false);
        }
        ToolAuthorizationDecision decision = authorizationPolicy.authorize(definition, invocation);
        if (decision.status() != ToolAuthorizationDecision.Status.ALLOW) {
            ToolErrorCode code = decision.status() == ToolAuthorizationDecision.Status.REQUIRE_CONFIRMATION
                    ? ToolErrorCode.TOOL_CONFIRMATION_REQUIRED
                    : ToolErrorCode.TOOL_NOT_AUTHORIZED;
            String publicMessage = decision.status() == ToolAuthorizationDecision.Status.REQUIRE_CONFIRMATION
                    ? "执行该工具前需要用户确认"
                    : "当前请求无权执行该工具";
            return failure(toolName, publicMessage, code, false);
        }
        ToolExecutionContext context = new ToolExecutionContext(UUID.randomUUID().toString(), definition);
        ToolExecutionResult result;
        try {
            result = new Chain(0).proceed(context, invocation);
        } catch (Exception error) {
            LOG.log(System.Logger.Level.WARNING,
                    "Tool execution chain failed, tool=" + toolName + ", exceptionType=" + error.getClass().getName(),
                    error);
            return ToolResultFactory.failed(toolName, DefaultToolErrorClassifier.INSTANCE.classify(error));
        }
        if (!result.isSuccess()) {
            return result;
        }

        // 中间件缓存或底层适配器返回的结果都必须经过同一输出契约，避免旁路产生未校验数据。
        List<String> outputErrors = contractValidator.validateOutput(definition, toEnvelope(result));
        if (!definition.getName().equals(result.toolName())) {
            outputErrors = merge(outputErrors, List.of("工具返回名称与注册契约不一致"));
        }
        if (!result.validationPassed()) {
            outputErrors = merge(outputErrors, List.of("status=success 时 validation_passed 必须为 true"));
        }
        for (ToolSemanticValidator validator : supportedSemanticValidators(toolName)) {
            outputErrors = merge(outputErrors, validator.validateOutput(definition, invocation.input(), result));
        }
        if (!outputErrors.isEmpty()) {
            return contractFailure(result, outputErrors);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> listToolSchemas() {
        return registry.listModelVisibleSchemas();
    }

    @Override
    public List<AgentToolDefinition> listToolDefinitions() {
        return registry.listDefinitions();
    }

    @Override
    public AgentToolDefinition getToolDefinition(String name) {
        return registry.findDefinition(name);
    }

    private ToolExecutionResult failure(
            String tool, String error, ToolErrorCode code, boolean retryable) {
        return ToolResultFactory.failed(tool, code, error, retryable);
    }

    private List<ToolSemanticValidator> supportedSemanticValidators(String toolName) {
        return semanticValidators.stream().filter(validator -> validator.supports(toolName)).toList();
    }

    private List<String> merge(List<String> left, List<String> right) {
        java.util.ArrayList<String> merged = new java.util.ArrayList<>(left == null ? List.of() : left);
        if (right != null) merged.addAll(right);
        return merged;
    }

    private com.fasterxml.jackson.databind.JsonNode toEnvelope(ToolExecutionResult result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("tool", result.toolName());
        envelope.put("status", result.status() == ToolExecutionStatus.SUCCESS ? "success" : "failed");
        envelope.put("validation_passed", result.validationPassed());
        envelope.put("validation_rules", result.validationRules());
        envelope.put("validation_errors", result.validationErrors());
        envelope.put("retryable", result.retryable());
        envelope.put("error_code", result.errorCode());
        envelope.put("data", result.data());
        envelope.put("metadata", result.metadata());
        if (result.error() != null) envelope.put("error", ToolResultFactory.publicError(result.error()));
        return json.toTree(envelope);
    }

    /** 契约失败时保留原始 data 供审计，但把结果改为 failed，阻止计划步骤继续消费。 */
    private ToolExecutionResult contractFailure(ToolExecutionResult result, List<String> errors) {
        var toolError = DefaultToolErrorClassifier.INSTANCE.classify(
                ToolErrorCode.TOOL_OUTPUT_CONTRACT_VIOLATION, "工具返回结果不符合契约", false);
        return new ToolExecutionResult(
                result.toolName(),
                ToolExecutionStatus.FAILED,
                false,
                merge(result.validationRules(), List.of("工具输出必须满足 output_schema 和业务语义契约")),
                merge(result.validationErrors(), errors),
                false,
                toolError.code(),
                result.data(),
                result.metadata(),
                toolError);
    }

    private final class Chain implements ToolInvocationChain {
        private final int index;

        private Chain(int index) {
            this.index = index;
        }

        @Override
        public ToolExecutionResult proceed(ToolExecutionContext context, ToolInvocation invocation) {
            if (index >= middlewares.size()) {
                return invoker.invoke(context.definition(), invocation);
            }
            return middlewares.get(index).invoke(context, invocation, new Chain(index + 1));
        }
    }
}
