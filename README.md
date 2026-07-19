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
- `javascope-user`: 与业务无关的统一用户、长期记忆、AI 会话和消息持久化模块
  - `BusinessAgentHandler`：stock、code 等业务的统一接入协议
  - `ConversationApplicationService`：会话归属校验、上下文加载、请求幂等和消息落库
  - `CurrentUserProvider`：登录态适配边界；当前由开发环境固定用户实现
  - MySQL 表：`app_user`、`conversation`、`conversation_message`、`agent_user_memory`
- `stockmind-common`: 公共基础能力
  - 文档文本抽取
  - 向量构建
  - OceanBase 向量表创建与检索
- `stockmind-bootstrap`: Spring Boot 启动、HTTP 接口、股票业务注入
  - `StockMindApplication`: 应用启动类
  - `AgentDemoController`: demo、流式对话、会话对话、记忆管理接口
  - `StockAgentBusinessConfiguration`: 股票场景 prompt 定制与澄清策略
  - 股票业务工具按类型拆分：`MarketDataTools`（行情/K线）、`TechnicalIndicatorTools`（技术指标）、`ResearchEvidenceTools`（新闻/知识库/结构化财报）、`FinancialMetricTools`（EPS/PE 确定性计算）、`StockAnalysisTools`（综合总结）
  - `UserMemoryService`: 用户记忆的读写、TTL 和 prompt 拼接
- `stockmind-domain`、`stockmind-application`、`stockmind-infrastructure`: 标准分层模块，目前主要作为后续业务扩展边界保留

## Agent 执行链路

1. `AgentDemoController` 接收用户输入；对话接口会拼接最近会话历史、用户记忆和上一轮澄清上下文。
2. `InputRouter` 调用模型判断路由：
   - `chat`: 闲聊，直接回复
   - `meta`: 身份、能力、使用方式等元问题，直接回复
   - `task`: 股票分析、查询、推荐、检索、规划等任务，进入 ReAct 工具链
3. 任务分支由 `ReActAgent` 按 direct/planned/react 共用的固定 10 轮上限循环：
   - 模型输出 `tool_calls` 或 `final_answer`
   - 需要工具时由 `ToolCallDispatcher` 执行
   - `direct` 面向单一用户目标，无需预先规划，也不根据业务证据探索下一步；工具失败后允许围绕同一目标进行有限替代或降级
   - `react` 不创建计划，下一步业务动作取决于上一工具返回的内容，需要动态调查或改变策略
   - `planned` 第一轮由模型在 `clarify_requirement` 和 `create_plan` 之间二选一；信息完整时创建固定的 `tool + input` 步骤链
   - planned 步骤失败、依赖阻塞或校验建议重规划时调用 `revise_plan`
   - 缺少完成目标必需且无法安全消解的业务语义时调用 `clarify_requirement`，并等待用户补充；参数格式、schema 和工具适配问题不属于需求澄清
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

### 工具强契约

`@AgentTool` 同时声明 `inputSchema` 和 `outputSchema`，两者既进入模型可见的工具定义，也由 Core 确定性执行：

- 调用前：`JsonSchemaToolContractValidator` 校验 type、required、properties、items、enum、pattern、format、数值范围和 additionalProperties。
- 调用后：无论结果来自业务方法、中间件还是缓存，统一校验完整工具 Envelope 和 data 输出结构。
- 业务语义：业务模块通过 `ToolSemanticValidator` 校验 JSON Schema 无法表达的跨字段关系，例如财报期间与报告类型一致、calculation_ready 与 EPS 来源一致、PE 必须等于 price/EPS。
- 计划约束：`required_outputs` 和步骤 `$ref` 必须来自工具 `outputSchema`；显式强契约工具禁止规划模型发明字段。
- 注册约束：只要显式 `inputSchema`/`outputSchema` 无法解析，应用立即启动失败，不再静默退回推断 Schema。

契约失败统一返回 `tool_input_contract_violation` 或 `tool_output_contract_violation`。输出契约失败会保留原始 data 用于审计，但状态改为 failed，后续计划步骤不能继续消费。

任务路由会同时给出执行模式：

| 模式 | 使用条件 | 典型任务 |
|---|---|---|
| `direct` | 用户目标单一，无需预先规划，也不需要根据业务证据探索下一步；工具失败后可围绕同一目标有限替代或降级 | 查询股票所属行业、读取指定行情、查询指定期间财报 |
| `planned` | 执行前可以确定完整步骤链，并为每一步指定 `tool + input + dependency` | 固定指标计算、确定性数据转换、强调审计的查询链 |
| `react` | 下一步业务动作取决于上一步得到的内容，需要动态调查或改变策略，无法预先可靠确定完整工具链 | 下跌原因调查、异常诊断、证据核验、自适应检索 |

“发生多次工具调用”不自动等于 `react` 或 `planned`。direct 可以在主工具失败后围绕同一目标进行有限替代或降级，但不能根据成功返回的业务证据扩展调查方向；如果下一步业务动作必须随中间内容调整，使用 `react`；如果第一次工具调用之前就能可靠写出完整可执行工具链，使用 `planned`。planned 首轮只暴露 `clarify_requirement` 与 `create_plan` 并要求二选一，react/direct 则从工具列表和运行时两层屏蔽 `create_plan`、`revise_plan`。

direct/react 使用 `selected_action` 单动作协议，每轮只能选择一个 `tool_call` 或 `final_answer`；planned 继续使用控制动作和计划步骤协议。react 在选择动作前输出结构化 `reasoning_update`，按“新增事实、假设更新、反证检查、信息缺口排序、可执行性判断、结果分支、停止判断”的固定顺序决策。Core 会校验声明工具与实际工具一致性，并要求动作对应最高优先级且当前可执行的缺口；证据引用中的说明文字会被规范化为纯 `source_step`，不能安全归一的单个事实或假设字段会被丢弃或回退，而不会阻止整轮合法动作。有效更新会合并进不可被普通历史裁剪的跨轮 `investigation_state`。

长 K 线的确定性摘要属于工具返回值，由 `stockmind-application` 在 `historical_bars.data.series_summary` 中产出，包含首尾收盘、区间涨跌、区间方向、末日涨跌、最高最低点和最大成交量。Context/Core 只透传、压缩通用 JSON，系统提示词不包含针对该工具或字段的特殊读取规则。

direct、planned 和 react 共用固定的 10 轮 Action/Observation 循环；被协议校验、重复动作或运行时门禁拒绝的动作同样消耗当前轮次。

执行层通过 `ExecutionStrategy` 抽象扩展。`ReActAgent` 统一创建 Trace、执行路由并选择策略：chat/meta 由 `DirectReplyExecutionStrategy` 回复；task/direct 和 task/react 由 `ReActExecutionStrategy` 执行；task/planned 由 `PlanReActExecutionStrategy` 执行。两个任务策略共用一套 Action/Observation 循环，差异仅在路由条件、可见工具和 Prompt 约束，避免形成嵌套 ReAct 内核。

计划重规划采用补丁协议：`revise_plan` 接收全部 `failed_steps`（含稳定 `step_id`），并返回 `replacements`。每个 replacement 指定 `replace_step_id` 和替代步骤列表；运行时仅替换这些失败或阻塞步骤，保留成功步骤及其输出，并按步骤指纹复用结果，避免重复调用。

每个计划步骤同时包含自然语言 `expected_outcome` 和机器契约 `required_outputs`。后者通过 JSON 路径、类型、nullable 和可选 expected_value 校验真实工具输出；即使工具返回 `status=success`，必需字段缺失、为 null、数据质量为 partial/invalid 或 calculation_ready=false 时，步骤仍会失败。多前置依赖使用 `depends_on_step_ids` 显式表达。

计划内工具调用也会写入 `observedActionFingerprints`。计划全部完成后运行时立即进入无工具的 final synthesis 状态，不再允许模型重复调用业务工具。

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

- `market_quote`: 通过腾讯财经公开 HTTP 接口返回 A 股、指数和 ETF 的实时行情；指定历史 `end_date` 时使用腾讯复权日 K
- `stock_snapshot_analysis`: 仅在用户明确要求分析/总结/解读时，基于已知行情、新闻或知识库信息生成简要总结、风险提示、数据局限和下一步建议
- `news_search`: 通过东财公开搜索接口按关键词检索真实新闻，并按请求时间窗过滤
- `knowledge_search`: 从 OceanBase 向量知识库检索财报/年报/季报证据片段

### 澄清策略

`ClarificationBusinessProvider` 用于补充业务能力说明、候选项、默认值和操作确认规则。股票场景会优先补齐：

- 分析对象
- 时间范围
- 分析维度
- 买入、卖出、下单等动作确认

澄清分为两类，但都由正常 Action Model 轮次决定，不增加固定的前置模型调用：

- `initial`：第一次行动判断时发现缺少 P0 对象、不可默认的关键口径或高风险确认；planned 首轮在澄清与建计划之间二选一。
- `runtime`：ReAct 根据工具观察发现必须由用户意图、业务口径或授权解决的阻塞；普通工具失败或仍可通过只读工具调查时不应澄清。

`clarify_requirement` 负责校验和生成结构化澄清上下文，下一轮仍由模型生成面向用户的自然回复。它只处理缺少业务信息、实质语义歧义和用户授权，不负责把自然语言转换成工具参数，也不得因格式、编码、schema、工具失败或能力限制要求用户澄清。`ask` 必须声明 `clarification_kind` 和 `materially_different_outcomes=true`；语义歧义还必须通过 `outcome_impacts` 给出至少两个实质不同的业务后果，运行时同时提供对应候选项。`missing_slots` 仅描述用户尚未表达的业务语义。澄清动作具有最高优先级，运行时会阻止它与 `create_plan` 或业务工具在同一轮混合执行。规划器只接收 `allowed_in_plan_step=true` 的工具；股票业务还会校验 `symbol/ticker` 的来源，禁止使用用户未明确提供的示例标的。

计划步骤失败或依赖阻塞后，运行时进入确定性的恢复门禁：模型只能调用 `revise_plan`，或停止调用工具并基于现有证据输出保守 `final_answer`。恢复期间业务工具、`create_plan` 和 `clarify_requirement` 均不可执行。进入该门禁前，框架会在同一个逻辑 tool call 内完成安全的瞬时错误重试；PlanExecutor 只看到最终结果。

`create_plan` 与 `revise_plan` 共用工具输入/输出契约和业务 `PlanSafetyValidator`。修订补丁必须先合并回当前计划，再对完整计划重新校验必填输入、未声明字段、跨步骤引用和业务工具链；`current_plan/failed_step/failed_steps/failure_context` 等恢复上下文始终由运行时权威状态覆盖模型输入。

## 配置

`stockmind-bootstrap/src/main/resources/application.yml` 会导入 `javascope-agent-spring-boot-starter/src/main/resources/application-agent-runtime.yml`。

建议通过环境变量覆盖敏感配置，不要把真实密钥、数据库密码提交到仓库：

```bash
export AGENT_RUNTIME_PROVIDER=openai
export AGENT_RUNTIME_MODEL=deepseek-chat
export AGENT_RUNTIME_API_KEY=你的模型 API Key
export AGENT_RUNTIME_BASE_URL=https://api.deepseek.com/v1
export AGENT_RUNTIME_FINAL_ANSWER_VALIDATION_ENABLED=false
export STOCKMIND_DATASOURCE_URL='jdbc:mysql://localhost:3306/stockmind?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8'
export STOCKMIND_DATASOURCE_USERNAME=你的数据库用户名
export STOCKMIND_DATASOURCE_PASSWORD=你的数据库密码
```

### Secret scanning

仓库使用 Gitleaks 在提交前和 CI 中检查硬编码密钥。首次克隆后安装本地钩子：

```bash
brew install pre-commit
pre-commit install
pre-commit run --all-files
```

本地钩子只扫描即将提交的内容；GitHub Actions 会在 push、pull request 和手动触发时扫描完整 Git 历史。不要使用 `SKIP=gitleaks` 绕过检查。若确认是误报，应在 `.gitleaks.toml` 中添加范围尽可能小且附带说明的 allowlist，禁止提交真实凭据后再通过忽略规则放行。

### 可观测性

Actuator 暴露 `health`、`info`、`metrics` 和 `prometheus` 端点。工具指标使用低基数的工具名、状态、错误类别和稳定错误码作为标签，不记录工具输入、原始输出或内部异常：

- `agent.tool.calls`：逻辑工具调用总量。
- `agent.tool.failures`：最终失败总量。
- `agent.tool.attempts`：实际 Attempt 总量。
- `agent.tool.retry.attempts`：自动重试 Attempt 总量。
- `agent.tool.retry.exhausted`：重试耗尽总量。
- `agent.tool.call.latency`：包含重试的逻辑调用耗时。
- `agent.tool.attempt.latency`：单次 Attempt 耗时。

Prometheus 抓取地址为 `/actuator/prometheus`。流式接口另外发送 `reasoning_update` 和 `tool_observation` SSE 事件；前者只包含经过运行时校验的结构化调查摘要，后者只包含安全错误视图、Attempt 摘要和允许恢复动作，不发送 Prompt、原始工具数据或模型私有推理文本。

常用运行时配置项：

- `java.agent-runtime.provider`: `openai` 或兼容提供方标识
- `java.agent-runtime.model`: 模型名称
- `java.agent-runtime.api-key`: 模型 API Key
- `java.agent-runtime.base-url`: OpenAI 兼容 Chat Completions 地址
- `java.agent-runtime.system-instruction`: 基础系统提示词
- direct、planned 和 react 的 Action/Observation 循环固定最多执行 10 轮，不提供额外轮次配置
- 最终合成始终额外保留 1 次模型调用，且禁止继续调用工具
- `java.agent-runtime.plan-max-retry`: 规划 JSON 重试次数
- `java.agent-runtime.tool-max-retries`: 单个幂等工具命中瞬时异常白名单后的额外重试次数，默认 `2`
- `java.agent-runtime.tool-retry-budget`: 一次 Agent 请求中所有工具共享的额外重试令牌数，默认 `6`
- `java.agent-runtime.tool-retry-base-delay-ms`: 指数退避基础时间，默认 `100ms`
- `java.agent-runtime.tool-retry-max-delay-ms`: `retryAfter` 或指数退避的等待上限，默认 `3000ms`
- `java.agent-runtime.request-timeout-ms`: 从 Agent 请求开始计算、跨模型阶段和全部工具共享的截止时间，默认 `180000ms`
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

### 持久化会话 API

```bash
# 创建 stock 会话
curl -X POST 'http://localhost:8080/api/v1/conversations' \
  -H 'Content-Type: application/json' \
  -d '{"businessCode":"stock","title":"贵州茅台分析"}'

# 发送消息；requestId 用于安全重试和幂等
curl -X POST 'http://localhost:8080/api/v1/conversations/{conversationId}/messages' \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"req-001","input":"分析一下 600519"}'

curl 'http://localhost:8080/api/v1/conversations?businessCode=stock'
curl 'http://localhost:8080/api/v1/conversations/{conversationId}/messages'

curl 'http://localhost:8080/api/v1/users/me'
curl -X PATCH 'http://localhost:8080/api/v1/users/me' \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"演示用户","avatarUrl":null}'
```

`businessCode` 在会话创建后不可修改。新版 API 的用户身份来自 `CurrentUserProvider`，不接受请求体伪造的 `userId`；尚未接入登录系统时由 `JAVASCOPE_DEVELOPMENT_USER_ID` 提供开发用户。

### 简单 demo

```bash
curl 'http://localhost:8080/api/agent/demo?input=帮我分析600519今天是否值得关注'
```

### SSE 流式 demo

```bash
curl -N 'http://localhost:8080/api/agent/demo/stream?input=帮我分析600519今天是否值得关注'
```

### 对话模式

```bash
curl -X POST 'http://localhost:8080/api/agent/chat' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"chat-001","userId":"demo-user","input":"先给我看600519短线观点"}'
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
