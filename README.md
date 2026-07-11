# stockmind-agent

股票分析 Agent 示例工程。当前项目由 Spring Boot 启动层、可复用 Agent 运行时、股票业务工具、会话记忆和知识库向量检索组成。

## 版本基线

- Java: `21`
- Spring Boot: `3.3.0`
- Reactor BOM: `2025.0.2`
- MySQL/OceanBase: 用于业务数据源与向量知识库
- Redis: 配置保留，当前会话上下文主要由应用内存维护

## 模块划分

- `javascope-agent-spi`: Agent 扩展契约、工具注解与共享协议
- `javascope-agent-context`: 独立的执行轨迹、上下文投影、预算和存储抽象
  - `ExecutionTrace` / `ExecutionLogStore`: 记录并查询可回放的完整执行过程
  - `InMemoryExecutionLogStore`: 调试阶段默认的进程内事件存储
  - `ContextManager` / `WorkingContext`: 从完整轨迹中选择本轮模型需要的计划、历史、约束和证据索引
  - `PromptBudget`: 限制每轮进入 Prompt 的字符、历史和证据数量
- `javascope-agent-core`: Agent 核心运行时，不依赖 Spring 和具体股票业务
  - `ReActAgent`: 主执行入口，负责路由、推理轮次、工具调用和最终回答
  - `InputRouter`: 将输入分为 `chat`、`meta`、`task`；非任务请求走直答分支
  - `ToolCallDispatcher` / `PlanExecutor`: 执行模型返回的工具调用和计划步骤
  - `CreatePlanTool` / `RevisePlanTool`: 规划与失败后重规划
  - `ClarifyRequirementTool`: 结构化澄清缺失槽位
  - `FinalAnswerSynthesizer` / `IndependentVerifierService`: 最终答案兜底和可选独立校验
  - `AgentPromptProvider`、`AgentBusinessPromptCustomizer`、`AgentToolExecutor`、`ClarificationBusinessProvider`: 业务扩展 SPI
- `javascope-agent-model-openai`: OpenAI Chat Completions 兼容模型适配器
- `javascope-agent-spring-boot-starter`: Spring Boot 自动配置与反射工具适配器
- `stockmind-common`: 公共基础能力
  - 文档文本抽取
  - 向量构建
  - OceanBase 向量表创建与检索
- `stockmind-bootstrap`: Spring Boot 启动、HTTP 接口、股票业务注入
  - `StockMindApplication`: 应用启动类
  - `AgentDemoController`: demo、流式对话、会话对话、记忆管理接口
  - `StockAgentBusinessConfiguration`: 股票场景 prompt 定制与澄清策略
  - `StockAgentTools`: 股票业务工具，包括行情、新闻、知识库检索
  - `UserMemoryService`: 用户记忆的读写、TTL 和 prompt 拼接
- `stockmind-domain`、`stockmind-application`、`stockmind-infrastructure`: 标准分层模块，目前主要作为后续业务扩展边界保留

## Agent 执行链路

1. `AgentDemoController` 接收用户输入；对话接口会拼接最近会话历史、用户记忆和上一轮澄清上下文。
2. `InputRouter` 调用模型判断路由：
   - `chat`: 闲聊，直接回复
   - `meta`: 身份、能力、使用方式等元问题，直接回复
   - `task`: 股票分析、查询、推荐、检索、规划等任务，进入 ReAct 工具链
3. 任务分支由 `ReActAgent` 在最多 `max-rounds` 轮内循环：
   - 模型输出 `tool_calls` 或 `final_answer`
   - 需要工具时由 `ToolCallDispatcher` 执行
   - 需要规划时先调用 `create_plan`
   - 步骤失败、依赖阻塞或校验建议重规划时调用 `revise_plan`
   - 缺少关键槽位时调用 `clarify_requirement`，并等待用户补充
4. 每个关键动作会同时写入两类数据：
   - 完整执行轨迹：用户输入、模型 Prompt/响应、工具请求/结果、最终答案等，供调试与回放
   - 工作上下文：仅选取当前计划、最近历史、失败约束和证据索引，供下一轮模型决策
5. 最终返回结构化 JSON，包含计划视图、兼容的执行日志、最终答案、风险标记和 `metadata.execution_id`。

## 强类型运行时协议

模型和工具调用不再以 JSON 字符串作为 Core 的边界协议：

- `AgentChatModelClient`: `ModelRequest -> ModelResult`
  - `ModelResult.Success`: JSON 树形式的结构化模型内容
  - `ModelResult.Failure`: 包含错误码、消息、HTTP 状态码和是否可重试
- `AgentToolExecutor`: `ToolInvocation -> ToolExecutionResult`
  - 统一包含工具名、执行状态、校验结果、错误码、重试标记、数据和元数据

OpenAI 客户端与反射工具执行器负责把外部 JSON 字符串转换为上述协议。业务工具当前仍可返回统一 JSON，兼容转换仅发生在 Spring starter 适配层。

## 工具执行架构

工具系统按职责拆分为四层：

```text
ToolRegistry -> ToolAuthorizationPolicy -> ToolMiddlewareChain -> ToolInvoker
```

- `ToolRegistry`：发现工具、查询定义并生成模型可见 schema。
- `ToolAuthorizationPolicy`：依据风险等级与确认要求决定允许、拒绝或等待确认。
- `ToolMiddleware`：默认包含固定窗口限流、写工具幂等、只读缓存和有限重试；完整调用过程仍由执行轨迹记录。
- `ToolInvoker`：执行反射、HTTP、MCP 或其他底层适配器；当前默认实现为 Spring 反射调用。

`DefaultAgentToolExecutor` 作为统一门面串联上述层次，Agent Core 不再依赖具体反射执行细节。

## 上下文与执行轨迹

完整执行轨迹和模型上下文是两条独立链路：

```text
Agent 执行
  ├─ ExecutionTrace -> ExecutionLogStore（完整 Prompt、模型响应、工具结果）
  └─ ContextManager -> WorkingContext -> PromptAssembler（仅本轮相关上下文）
```

这保证调试时可以通过 `metadata.execution_id` 查询完整过程，同时避免把完整历史和原始工具输出持续拼入模型 Prompt。

默认实现使用内存存储，适合本地调试。可在业务侧替换以下 Bean：

- `ExecutionLogStore`：接入 MySQL、Redis、Kafka 或 OpenTelemetry
- `ContextManager`：接入摘要压缩、RAG、向量召回或领域化证据筛选
- `PromptAssembler`：调整 Prompt 段落顺序、上下文裁剪与输出格式

## 业务扩展点

### Prompt 定制

业务侧通过 `AgentBusinessPromptCustomizer` 追加场景规则：

- `customizeRoutePrompt`
- `customizeDirectReplyPrompt`
- `customizeActionPrompt`
- `customizePlanPrompt`
- `customizeValidationPrompt`
- `customizeIndependentValidationPrompt`
- `customizeClarificationPrompt`

股票场景实现位于 `stockmind-bootstrap/src/main/java/com/stockmind/bootstrap/StockAgentBusinessConfiguration.java`。

### 工具注入

在 Spring Bean 方法上使用 `@AgentTool` 即可注册为模型可调用工具。当前股票工具：

- `market_quote`: 根据 `symbol` 返回 mock 行情
- `stock_snapshot_analysis`: 仅在用户明确要求分析/总结/解读时，基于已知行情、新闻或知识库信息生成简要总结、风险提示、数据局限和下一步建议
- `news_search`: 按关键词检索新闻，当前为失败占位实现
- `knowledge_search`: 从 OceanBase 向量知识库检索财报/年报/季报证据片段

### 澄清策略

`ClarificationBusinessProvider` 用于补充业务能力说明、候选项、默认值和操作确认规则。股票场景会优先补齐：

- 分析对象
- 时间范围
- 分析维度
- 买入、卖出、下单等动作确认

## 配置

`stockmind-bootstrap/src/main/resources/application.yml` 会导入 `javascope-agent-spring-boot-starter/src/main/resources/application-agent-runtime.yml`。

建议通过环境变量覆盖敏感配置，不要把真实密钥、数据库密码提交到仓库：

```bash
export AGENT_RUNTIME_PROVIDER=openai
export AGENT_RUNTIME_MODEL=deepseek-chat
export AGENT_RUNTIME_API_KEY=你的模型 API Key
export AGENT_RUNTIME_BASE_URL=https://api.deepseek.com/v1
export AGENT_RUNTIME_FINAL_ANSWER_VALIDATION_ENABLED=false
```

常用运行时配置项：

- `java.agent-runtime.provider`: `openai` 或兼容提供方标识
- `java.agent-runtime.model`: 模型名称
- `java.agent-runtime.api-key`: 模型 API Key
- `java.agent-runtime.base-url`: OpenAI 兼容 Chat Completions 地址
- `java.agent-runtime.system-instruction`: 基础系统提示词
- `java.agent-runtime.max-rounds`: ReAct 最大推理轮次
- `java.agent-runtime.plan-max-retry`: 规划 JSON 重试次数
- `java.agent-runtime.final-answer-validation-enabled`: 是否开启最终答案独立校验
- `java.agent-runtime.temperature`: 模型温度
- `java.agent-runtime.timeout-seconds`: 模型请求超时
- `java.agent-runtime.context-max-prompt-characters`: 单轮 Action Prompt 的字符预算，默认 `24000`
- `java.agent-runtime.context-max-history-items`: 单轮选择的最近执行日志数量，默认 `6`
- `java.agent-runtime.context-max-evidence-items`: 单轮选择的证据摘要数量，默认 `8`

知识库向量配置：

- `stockmind.knowledge.vector.table`: 向量表名
- `stockmind.knowledge.vector.dimensions`: 向量维度
- `stockmind.knowledge.vector.default-top-k`: 默认检索条数

## 运行

当前仓库未包含 Maven Wrapper，需要本机安装 Maven。

```bash
cd /Users/huang/Documents/agent/stockmind-agent
mvn -pl stockmind-bootstrap -am spring-boot:run
```

## HTTP 接口

### 简单 demo

```bash
curl 'http://localhost:8080/api/agent/demo?input=帮我分析AAPL今天是否值得关注'
```

### SSE 流式 demo

```bash
curl -N 'http://localhost:8080/api/agent/demo/stream?input=帮我分析AAPL今天是否值得关注'
```

### 对话模式

```bash
curl -X POST 'http://localhost:8080/api/agent/chat' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"chat-001","userId":"demo-user","input":"先给我看AAPL短线观点"}'
```

同一 `sessionId` 会保留最近 10 轮对话。重复请求会在短时间内命中缓存，避免重复调用模型。

### 用户记忆

```bash
curl 'http://localhost:8080/api/agent/memory?userId=demo-user'

curl -X PUT 'http://localhost:8080/api/agent/memory/business_preferences?userId=demo-user' \
  -H 'Content-Type: application/json' \
  -d '{"value":"偏保守，只关注大盘股","ttlSeconds":86400}'

curl -X DELETE 'http://localhost:8080/api/agent/memory/business_preferences?userId=demo-user'
```

也可以在对话中使用 `记住 key=value` 写入支持的记忆项。当前支持 `language`、`name`、`tone`、`timezone`、`business_preferences`。

## 前端页面

启动服务后访问：

```text
http://localhost:8080/chat.html
```

页面支持普通对话、流式输出和记忆管理。
