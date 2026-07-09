package com.agent.javascope.config;

import com.agent.javascope.prompt.AgentBusinessPromptCustomizer;
import com.agent.javascope.prompt.DefaultAgentPromptProvider;
import com.agent.javascope.prompt.LayeredAgentPromptProvider;
import com.agent.javascope.tools.ReflectiveAgentToolExecutor;
import com.agent.javascope.chat.AgentChatModelClient;
import com.agent.javascope.chat.OpenAiCompatibleAgentChatModelClient;
import com.agent.javascope.prompt.AgentPromptProvider;
import com.agent.javascope.tools.AgentToolExecutor;
import com.agent.javascope.tools.ClarificationBusinessProvider;
import com.agent.javascope.tools.ClarifyRequirementTool;
import com.agent.javascope.tools.CreatePlanTool;
import com.agent.javascope.tools.RevisePlanTool;
import com.agent.javascope.tools.StepValidatorTool;
import com.agent.javascope.util.AgentJsonCodecUtil;
import com.agent.javascope.verifier.IndependentVerifierService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeAutoConfiguration {

    @Bean
    @Primary
    public AgentPromptProvider agentPromptProvider(ObjectProvider<AgentBusinessPromptCustomizer> customizers) {
        List<AgentBusinessPromptCustomizer> orderedCustomizers = customizers.orderedStream().toList();
        return new LayeredAgentPromptProvider(new DefaultAgentPromptProvider(), orderedCustomizers);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToolExecutor agentToolExecutor() {
        return new ReflectiveAgentToolExecutor(applicationContext);
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
            AgentRuntimeProperties properties) {
        return new CreatePlanTool(
                promptProvider,
                agentToolExecutor,
                agentChatModelClient,
                json,
                properties.getPlanMaxRetry());
    }

    @Bean
    @ConditionalOnMissingBean
    public RevisePlanTool revisePlanTool(
            AgentPromptProvider promptProvider,
            AgentToolExecutor agentToolExecutor,
            AgentChatModelClient agentChatModelClient,
            AgentJsonCodecUtil json,
            AgentRuntimeProperties properties) {
        return new RevisePlanTool(
                promptProvider,
                agentToolExecutor,
                agentChatModelClient,
                json,
                properties.getPlanMaxRetry());
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
            AgentPromptProvider promptProvider, AgentChatModelClient agentChatModelClient, AgentJsonCodecUtil json) {
        return new IndependentVerifierService(promptProvider, agentChatModelClient, json);
    }

    private final ApplicationContext applicationContext;

    public AgentRuntimeAutoConfiguration(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
}
