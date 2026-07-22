package com.agent.javascope.config;

import com.agent.javascope.prompt.AgentBusinessPromptCustomizer;
import com.agent.javascope.prompt.DefaultAgentPromptProvider;
import com.agent.javascope.prompt.LayeredAgentPromptProvider;
import com.agent.javascope.tools.ReflectiveAgentToolExecutor;
import com.agent.javascope.tool.execution.DefaultAgentToolExecutor;
import com.agent.javascope.tool.authorization.DefaultToolAuthorizationPolicy;
import com.agent.javascope.tool.middleware.RetryToolMiddleware;
import com.agent.javascope.tool.middleware.DefaultToolRetryPolicy;
import com.agent.javascope.tool.middleware.TimeLimitToolMiddleware;
import com.agent.javascope.tool.middleware.CacheToolMiddleware;
import com.agent.javascope.tool.middleware.IdempotencyToolMiddleware;
import com.agent.javascope.tool.middleware.RateLimitToolMiddleware;
import com.agent.javascope.model.AgentChatModelClient;
import com.agent.javascope.model.openai.OpenAiCompatibleAgentChatModelClient;
import com.agent.javascope.runtime.AgentRuntimeProperties;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import com.agent.javascope.tool.authorization.ToolAuthorizationPolicy;
import com.agent.javascope.tool.invocation.ToolInvoker;
import com.agent.javascope.tool.middleware.ToolMiddleware;
import com.agent.javascope.tool.middleware.ToolExecutionObserver;
import com.agent.javascope.tool.middleware.ToolRetryPolicy;
import com.agent.javascope.tool.registry.ToolRegistry;
import com.agent.javascope.tool.runtime.ClarificationBusinessProvider;
import com.agent.javascope.tool.runtime.PlanSafetyValidator;
import com.agent.javascope.tool.contract.JsonSchemaToolContractValidator;
import com.agent.javascope.tool.contract.ToolContractValidator;
import com.agent.javascope.tool.contract.ToolSemanticValidator;
import com.agent.javascope.tools.clarification.ClarifyRequirementTool;
import com.agent.javascope.tools.planning.CreatePlanTool;
import com.agent.javascope.tools.planning.RevisePlanTool;
import com.agent.javascope.tools.validation.StepValidatorTool;
import com.agent.javascope.json.AgentJsonCodecUtil;
import com.agent.javascope.verifier.IndependentVerifierService;
import com.agent.javascope.verifier.FinalAnswerSemanticValidator;
import com.agent.javascope.agent.runtime.ReActAgent;
import com.agent.javascope.agent.prompt.DefaultPromptAssembler;
import com.agent.javascope.agent.prompt.PromptAssembler;
import com.agent.javascope.context.projection.ContextManager;
import com.agent.javascope.context.trace.ExecutionLogStore;
import com.agent.javascope.context.projection.InMemoryContextManager;
import com.agent.javascope.context.trace.InMemoryExecutionLogStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@AutoConfiguration
public class AgentRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "java.agent-runtime")
    public AgentRuntimeProperties agentRuntimeProperties() {
        return new AgentRuntimeProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentJsonCodecUtil agentJsonCodecUtil() {
        return new AgentJsonCodecUtil();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutionLogStore executionLogStore() {
        return new InMemoryExecutionLogStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextManager contextManager() {
        return new InMemoryContextManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptAssembler promptAssembler(AgentJsonCodecUtil json) {
        return new DefaultPromptAssembler(json);
    }

    @Bean
    @Primary
    public AgentPromptProvider agentPromptProvider(ObjectProvider<AgentBusinessPromptCustomizer> customizers) {
        List<AgentBusinessPromptCustomizer> orderedCustomizers = customizers.orderedStream().toList();
        return new LayeredAgentPromptProvider(new DefaultAgentPromptProvider(), orderedCustomizers);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReflectiveAgentToolExecutor reflectiveToolAdapter() {
        return new ReflectiveAgentToolExecutor(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ReflectiveAgentToolExecutor adapter) {
        return adapter;
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolInvoker toolInvoker(ReflectiveAgentToolExecutor adapter) {
        return adapter;
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolAuthorizationPolicy toolAuthorizationPolicy() {
        return new DefaultToolAuthorizationPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRetryPolicy toolRetryPolicy() {
        return DefaultToolRetryPolicy.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean(name = "retryToolMiddleware")
    @Order(40)
    public ToolMiddleware retryToolMiddleware(
            AgentRuntimeProperties properties,
            ToolRetryPolicy toolRetryPolicy,
            ToolExecutionObserver toolExecutionObserver) {
        return new RetryToolMiddleware(
                properties.getToolMaxRetries(),
                properties.getToolRetryBaseDelayMs(),
                properties.getToolRetryMaxDelayMs(),
                toolRetryPolicy,
                toolExecutionObserver);
    }

    @Bean(name = "agentToolTimeoutExecutor", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "agentToolTimeoutExecutor")
    public ExecutorService agentToolTimeoutExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("agent-tool-attempt-", 0).factory());
    }

    @Bean
    @ConditionalOnMissingBean(name = "timeLimitToolMiddleware")
    @Order(50)
    public ToolMiddleware timeLimitToolMiddleware(
            @Qualifier("agentToolTimeoutExecutor") ExecutorService executor) {
        return new TimeLimitToolMiddleware(executor);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutionObserver toolExecutionObserver(ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.orderedStream().findFirst().orElse(null);
        return registry == null ? ToolExecutionObserver.NOOP : new MicrometerToolExecutionObserver(registry);
    }

    @Bean
    @ConditionalOnMissingBean(name = "rateLimitToolMiddleware")
    @Order(10)
    public ToolMiddleware rateLimitToolMiddleware() {
        return new RateLimitToolMiddleware(1_000);
    }

    @Bean
    @ConditionalOnMissingBean(name = "idempotencyToolMiddleware")
    @Order(20)
    public ToolMiddleware idempotencyToolMiddleware() {
        return new IdempotencyToolMiddleware();
    }

    @Bean
    @ConditionalOnMissingBean(name = "cacheToolMiddleware")
    @Order(30)
    public ToolMiddleware cacheToolMiddleware() {
        return new CacheToolMiddleware();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolContractValidator toolContractValidator() {
        return new JsonSchemaToolContractValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToolExecutor agentToolExecutor(
            ToolRegistry registry,
            ToolAuthorizationPolicy authorizationPolicy,
            ObjectProvider<ToolMiddleware> middlewares,
            ObjectProvider<ToolSemanticValidator> semanticValidators,
            ToolInvoker invoker,
            AgentJsonCodecUtil json,
            ToolContractValidator contractValidator,
            ToolExecutionObserver toolExecutionObserver) {
        return new DefaultAgentToolExecutor(
                registry,
                authorizationPolicy,
                middlewares.orderedStream().toList(),
                invoker,
                json,
                contractValidator,
                semanticValidators.orderedStream().toList(),
                toolExecutionObserver);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentChatModelClient agentChatModelClient(
            AgentRuntimeProperties properties,
            AgentJsonCodecUtil json) {
        return new OpenAiCompatibleAgentChatModelClient(properties, json);
    }

    @Bean
    @ConditionalOnMissingBean
    public CreatePlanTool createPlanTool(
            AgentPromptProvider promptProvider,
            AgentToolExecutor agentToolExecutor,
            AgentChatModelClient agentChatModelClient,
            AgentJsonCodecUtil json,
            AgentRuntimeProperties properties,
            ObjectProvider<PlanSafetyValidator> planSafetyValidator) {
        return new CreatePlanTool(
                promptProvider,
                agentToolExecutor,
                agentChatModelClient,
                json,
                properties.getPlanMaxRetry(),
                planSafetyValidator.getIfAvailable(() -> new PlanSafetyValidator() {}));
    }

    @Bean
    @ConditionalOnMissingBean
    public RevisePlanTool revisePlanTool(
            AgentPromptProvider promptProvider,
            AgentToolExecutor agentToolExecutor,
            AgentChatModelClient agentChatModelClient,
            AgentJsonCodecUtil json,
            AgentRuntimeProperties properties,
            ObjectProvider<PlanSafetyValidator> planSafetyValidator) {
        return new RevisePlanTool(
                promptProvider,
                agentToolExecutor,
                agentChatModelClient,
                json,
                properties.getPlanMaxRetry(),
                planSafetyValidator.getIfAvailable(() -> new PlanSafetyValidator() {}));
    }

    @Bean
    @ConditionalOnMissingBean
    public StepValidatorTool stepValidatorTool(AgentJsonCodecUtil json) {
        return new StepValidatorTool(json);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClarifyRequirementTool clarifyRequirementTool(ObjectProvider<ClarificationBusinessProvider> provider) {
        return new ClarifyRequirementTool(provider.getIfAvailable(() -> new ClarificationBusinessProvider() {
        }));
    }

    @Bean
    @ConditionalOnMissingBean
    public IndependentVerifierService independentVerifierService(
            AgentPromptProvider promptProvider,
            AgentChatModelClient agentChatModelClient,
            AgentJsonCodecUtil json,
            ObjectProvider<FinalAnswerSemanticValidator> semanticValidators) {
        return new IndependentVerifierService(
                promptProvider, agentChatModelClient, json, semanticValidators.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReActAgent reActAgent(
            AgentRuntimeProperties properties,
            AgentPromptProvider promptProvider,
            AgentToolExecutor toolExecutor,
            AgentChatModelClient modelClient,
            AgentJsonCodecUtil json,
            StepValidatorTool stepValidatorTool,
            IndependentVerifierService independentVerifierService,
            ExecutionLogStore executionLogStore,
            ContextManager contextManager,
            PromptAssembler promptAssembler) {
        return new ReActAgent(
                properties,
                promptProvider,
                toolExecutor,
                modelClient,
                json,
                stepValidatorTool,
                independentVerifierService,
                executionLogStore,
                contextManager,
                promptAssembler);
    }

    @Bean
    public SmartInitializingSingleton agentRuntimeInitializer(ReActAgent agent) {
        return agent::initialize;
    }

    private final ApplicationContext applicationContext;

    public AgentRuntimeAutoConfiguration(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
}
