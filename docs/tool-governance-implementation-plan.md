# Agent 工具治理与编排实施方案

> 适用项目：`stockmind-agent` / `javascope-agent-*`  
> 目标版本：Tool Contract V2  
> 建议周期：4 周，2～3 名研发 + 1 名业务/测试兼职  
> 核心原则：模型负责理解和选择，运行时负责约束、解析、校验与留痕。

## 1. 目标与验收结果

本轮不继续扩充工具数量，先把现有工具整理成可选择、可组合、可校验、可回放的标准能力。

上线验收目标：

1. 所有模型可见业务工具都有明确的适用条件、排除条件、严格输入 Schema 和严格输出 Schema。
2. 每个最终入参都能追溯到用户输入、已确认上下文、上游工具输出或系统默认值。
3. 缺参、歧义和高风险推断在调用前被阻断，不能依靠业务工具报错兜底。
4. 多工具之间只通过显式 `$ref` 和稳定字段传值，不再按同名字段自动拼接。
5. 重名工具、非法 Schema、模型可见的宽松 `Map` 契约在应用启动或 CI 阶段失败。
6. 试点工具集的工具选择准确率 ≥ 95%，参数准确率 ≥ 97%，危险误调用为 0。

## 2. 当前基线与主要问题

项目已有较好的基础：`AgentToolDefinition`、输入/输出 Schema、`ToolContractValidator`、`ToolSemanticValidator`、统一 `ToolExecutionResult`、计划步骤引用、授权和中间件都已经存在。因此采用增量升级，不重写 Agent 内核。

当前需要治理的风险：

| 位置 | 当前行为 | 风险 | 优先级 |
|---|---|---|---|
| `ReflectiveAgentToolExecutor` | 工具重名时警告后覆盖 | 实际执行工具不确定 | P0 |
| `ReflectiveAgentToolExecutor.resolveSchema` | Schema 解析失败后回退 | 错误契约进入生产 | P0 |
| `PlanInputResolver.collectPriorData` | 将前序步骤同名字段自动回填 | 字段碰撞、来源丢失、错误串值 | P0 |
| `@AgentTool.description` | 适用与排除条件主要写在自然语言中 | 工具选择容易歧义 | P0 |
| `@ToolField` | 只支持描述、必填、枚举、默认值 | 无法表达参数来源、推断和确认策略 | P1 |
| 多数业务方法 | 使用 `Map<String,Object>` | 编译期和自动推断能力弱 | P1 |
| 部分工具 | 未声明严格输出 Schema | 下游引用稳定性不足 | P1 |
| 工具示例 | 缺少反例和易混淆工具对比 | 模型容易“都能用” | P1 |

## 3. 目标架构

```text
用户输入 / 已确认上下文 / 上游结果
                 │
                 ▼
        Intent & Slot Extraction
     （候选值 + 来源 + 置信/确认策略）
                 │
                 ▼
       Tool Selection Gate
  （use_when / do_not_use_when / 冲突规则）
                 │
                 ▼
       Argument Resolution
  （显式绑定、优先级、规范化、禁止猜测）
                 │
                 ▼
   Contract + Semantic + Authorization Gate
                 │
                 ▼
             Tool Executor
                 │
                 ▼
       Standard Result Envelope
  （data / references / error / metadata）
                 │
                 ├── ExecutionTrace：完整审计
                 └── WorkingContext：精简供后续决策
```

模型可以提出候选工具和候选参数，但运行时必须决定这些参数是否可用、是否需要澄清、是否允许执行。

## 4. Tool Contract V2

### 4.1 工具级契约

在 `AgentToolDefinition` 和 `@AgentTool` 增加以下字段：

```java
String[] useWhen() default {};
String[] doNotUseWhen() default {};
String[] requiredContext() default {};
String[] conflictsWith() default {};
String[] preferredOver() default {};
String[] capabilities() default {};
String[] produces() default {};
```

字段含义：

- `useWhen`：满足哪些业务意图时使用。
- `doNotUseWhen`：看似相关但禁止使用的场景。
- `requiredContext`：进入参数解析前必须存在的业务槽位。
- `conflictsWith`：同一轮原则上不能同时选择的工具。
- `preferredOver`：能力重叠时的优先级关系。
- `capabilities`：用于候选工具召回的稳定能力标签。
- `produces`：输出的稳定业务对象或字段族。

工具描述不再承担全部选择规则。Prompt 展示时按固定段落生成：用途、使用条件、禁止条件、必需输入、关键输出、风险。

### 4.2 参数级契约

扩展 `@ToolField`，或在 JSON Schema 中采用 `x-agent-*` 扩展字段：

```json
{
  "type": "string",
  "description": "股票代码，规范化为交易所标准代码",
  "minLength": 1,
  "x-agent-source-policy": [
    "explicit_user_input",
    "confirmed_context",
    "upstream_reference"
  ],
  "x-agent-inference": "CONDITIONAL",
  "x-agent-confirmation": "WHEN_AMBIGUOUS",
  "x-agent-normalizer": "stock_symbol",
  "x-agent-sensitive": false,
  "x-agent-examples": ["AAPL", "600519.SH"]
}
```

统一枚举：

```java
enum ArgumentInferencePolicy {
    SAFE,          // 可确定性规范化或使用无风险默认值
    CONDITIONAL,   // 满足规则时可推断，否则澄清
    FORBIDDEN      // 必须来自用户明确输入、确认上下文或上游权威输出
}

enum ArgumentConfirmationPolicy {
    NEVER,
    WHEN_AMBIGUOUS,
    WHEN_INFERRED,
    ALWAYS
}
```

参数来源优先级固定为：

```text
EXPLICIT_USER_INPUT
> USER_CONFIRMED_CONTEXT
> UPSTREAM_TOOL_REFERENCE
> BUSINESS_CONTEXT
> SYSTEM_DEFAULT
```

高优先级值不能被低优先级覆盖；同优先级出现冲突时不得静默选择。

### 4.3 统一输出契约

保留现有 `ToolExecutionResult`，但将模型和计划可消费的映射统一为：

```json
{
  "tool": "market_quote",
  "status": "success",
  "validation_passed": true,
  "validation_rules": [],
  "validation_errors": [],
  "retryable": false,
  "error_code": "",
  "data": {},
  "references": {
    "instrument_id": "NASDAQ:AAPL"
  },
  "artifacts": [],
  "metadata": {
    "invocation_id": "inv_...",
    "contract_version": "2.0.0",
    "source": "market-provider",
    "observed_at": "2026-07-14T10:00:00+08:00"
  }
}
```

要求：

- 模型可见业务工具必须 `strict_output_contract=true`。
- 下游优先引用 `references` 中的稳定 ID，不用展示名称做关联。
- 时间敏感数据必须输出 `observed_at` 或业务时间字段。
- 错误使用稳定 `error_code`，自然语言仅用于诊断。
- `metadata` 不作为业务计划的默认数据源。

## 5. 用户输入到工具参数的提取方案

### 5.1 不增加独立模型调用，先采用“候选参数 + 运行时决议”

第一阶段继续让 Action Model 输出工具调用，但把输出视为候选参数。新增 `ToolArgumentResolver`，在合同校验前完成以下流程：

1. 读取模型候选参数。
2. 结合原始用户输入、已确认槽位、显式上游 `$ref` 和允许的默认值生成候选集合。
3. 进行确定性规范化，例如代码大小写、日期格式、枚举别名。
4. 按来源优先级解决单值。
5. 根据字段策略输出 `RESOLVED`、`MISSING`、`AMBIGUOUS`、`CONFIRMATION_REQUIRED` 或 `INVALID`。
6. 只有全部必填字段为 `RESOLVED` 才进入工具执行。

建议的数据结构：

```java
public record ResolvedArgument(
        String path,
        JsonNode value,
        ArgumentSource source,
        String sourceReference,
        ResolutionStatus status,
        List<String> evidence,
        boolean normalized) {}

public record ToolArgumentResolution(
        String toolName,
        JsonNode resolvedInput,
        List<ResolvedArgument> arguments,
        List<String> missingPaths,
        List<String> ambiguousPaths,
        boolean confirmationRequired) {}
```

### 5.2 多种输入情况的处理矩阵

| 输入情况 | 示例 | 处理方式 |
|---|---|---|
| 明确完整 | “查 AAPL 2026-07-01 到 07-10 日线” | 提取并规范化后执行 |
| 可安全默认 | “查 AAPL 最近行情” | 使用工具声明的只读默认窗口，并记录 `SYSTEM_DEFAULT` |
| 可确定性归一 | “苹果”“aapl” | 实体解析或大小写归一；多个匹配时澄清 |
| 关键字段缺失 | “帮我看下这只股票”且无上下文对象 | 返回缺失槽位，调用澄清工具 |
| 语义歧义 | “看下苹果最近表现”，无法确定市场/时间范围 | 只有结果实质不同时澄清 |
| 上下文承接 | 上轮已确认 `AAPL`，本轮“再看 RSI” | 使用 `USER_CONFIRMED_CONTEXT` |
| 上游输出 | 财报工具输出 EPS，计算工具消费 | 必须使用显式 `$ref` |
| 用户值与上游冲突 | 用户说价格 100，上游行情为 105 | 按业务字段策略选择或澄清，不能静默覆盖 |
| 高风险参数 | 金额、收件人、下单数量 | `FORBIDDEN + ALWAYS`，明确确认后执行 |

### 5.3 澄清边界

只有以下情况向用户提问：

- 缺少完成业务目标所必需的字段。
- 存在两个以上会导致实质不同结果的候选值。
- 字段策略要求确认或工具具有外部副作用。

Schema 格式错误、字段类型错误、工具不可用和内部映射失败属于系统错误，不转嫁给用户澄清。

## 6. 多工具输入输出保持方案

### 6.1 禁止隐式同名字段合并

移除 `PlanInputResolver.collectPriorData` 的自动回填逻辑。V2 只允许三种输入来源：

```json
{
  "symbol": {"$ref": "context.confirmed.symbol"},
  "price": {"$ref": "steps.quote.data.price"},
  "report_period": "2025-12-31"
}
```

- 字面量：计划中明确给出。
- `context.*`：来自运行时管理的已确认上下文。
- `steps.<step_id>.*`：来自指定成功步骤。

不再使用数字步骤序号作为新计划的首选引用，统一使用稳定 `step_id`；旧格式保留一版兼容并记录弃用日志。

### 6.2 引用在执行前验证

增强 `PlanToolReferenceInspector`：

- 计划创建时检查引用的工具输出 Schema 中是否存在目标路径。
- 检查源字段类型是否兼容目标输入字段类型。
- 检查依赖图中源步骤是否为当前步骤的祖先。
- 禁止引用失败、跳过或未完成步骤。
- 禁止业务输入引用 `metadata`，除非目标字段明确允许。

### 6.3 保存参数绑定清单

每次调用在 Trace 中新增 `ARGUMENTS_RESOLVED` 事件：

```json
{
  "invocation_id": "inv_123",
  "tool": "financial_metric_calculator",
  "bindings": [
    {
      "target": "price",
      "source": "UPSTREAM_TOOL_REFERENCE",
      "reference": "steps.quote.data.price",
      "value_hash": "..."
    }
  ]
}
```

敏感字段只存摘要或脱敏值。这样可以完整回答“这个参数为什么是这个值”。

## 7. 工具选择消歧方案

### 7.1 两阶段选择

工具较少时仍可全量展示；超过 20 个模型可见业务工具后启用两阶段：

1. 按 namespace、capability、用户意图和执行模式召回 5～8 个候选工具。
2. Action Model 只在候选工具中选择。

系统流程工具和业务工具分别治理，不参与同一业务能力竞争。

### 7.2 冲突矩阵

首批建立以下显式规则：

| 用户意图 | 首选工具 | 排除/次选工具 |
|---|---|---|
| 当前价格、涨跌 | `market_quote` | 不调用 `historical_bars` |
| 原始 K 线、图表数据 | `historical_bars` | 不用 `market_quote` 替代 |
| 未指定单项技术指标 | `technical_indicator_snapshot` | 不并发调用全部单项指标工具 |
| 明确 RSI/MACD 等单指标 | 对应单项工具 | 不调用完整 snapshot，除非用户还要综合分析 |
| 财报原始证据检索 | `knowledge_search` | 不用计算器生成事实 |
| 结构化财务字段 | `financial_report_metrics` | 不直接让模型从文本抄数 |
| EPS/PE 确定性计算 | `financial_metric_calculator` | 不让分析工具自行计算 |
| 获取真实数据并形成综合快照 | `stock_snapshot_analysis` | 自包含调用公开接口，不接收模型拼装证据 |

### 7.3 工具定义质量门禁

新增 `ToolDefinitionLinter`，在 CI 和应用启动时校验：

- 工具名全局唯一；重名直接启动失败。
- 模型可见业务工具必须有 `useWhen`、`doNotUseWhen`、input/output Schema 和正反例。
- 根对象必须设置 `additionalProperties=false`。
- 必填字符串应有 `minLength`，枚举必须显式列值。
- Schema 解析失败直接失败，不允许回退。
- `Map` 入参若无显式严格 Schema 则失败。
- 写工具必须声明风险级别、幂等策略和确认策略。
- `produces` 中声明的路径必须存在于 output Schema。
- `preferredOver`、`conflictsWith` 引用的工具必须存在，且不能形成优先级环。

开发环境可以先以 warning 模式运行一周，CI 从第一天按 error 执行；生产环境必须 error。

## 8. 代码改造清单

### 8.1 `javascope-agent-spi`

- 扩展 `AgentToolDefinition` 和 `@AgentTool` 的选择语义字段。
- 扩展 `@ToolField` 或支持 `x-agent-*` 参数策略。
- 新增 `ArgumentSource`、`ArgumentInferencePolicy`、`ArgumentConfirmationPolicy`。
- 为 `ToolExecutionResult`/映射 Envelope 增加 `references`、`artifacts` 或在 metadata 外建立正式字段。
- Contract 版本升级到 `2.0.0`。

### 8.2 `javascope-agent-core`

- 新增 `ToolArgumentResolver`、`ToolArgumentResolution`、`ResolvedArgument`。
- `ToolCallDispatcher` 在授权和执行前接入参数决议门禁。
- `PlanInputResolver` 删除同名自动回填，仅处理显式引用。
- `PlanToolReferenceInspector` 增加路径、类型和依赖校验。
- 新增 `ToolDefinitionLinter` 与选择冲突校验。
- `ExecutionEventType` 增加 `ARGUMENTS_RESOLVED`、`TOOL_SELECTION_REJECTED`。
- Prompt 由工具定义的结构化字段生成固定选择说明。

### 8.3 `javascope-agent-spring-boot-starter`

- `ReflectiveAgentToolExecutor` 遇到重名立即抛出启动异常。
- 显式 Schema 解析失败立即抛出启动异常。
- 注册完成后运行 `ToolDefinitionLinter`。
- 提供配置项：

```yaml
java:
  agent-runtime:
    tool-contract:
      version: 2
      lint-mode: error
      allow-legacy-step-index-ref: true
      allow-implicit-prior-data-merge: false
```

### 8.4 `stockmind-bootstrap`

先治理 8 个高频/易混淆工具：

1. `market_quote`
2. `historical_bars`
3. `technical_indicator_snapshot`
4. `rsi_analysis`
5. `news_search`
6. `knowledge_search`
7. `financial_report_metrics`
8. `financial_metric_calculator`

为其补齐 V2 元数据、严格输出 Schema、至少 2 个正例和 2 个反例。第二批再治理其余单项技术指标与汇总工具。

## 9. 分阶段实施计划

### 第 1 周：标准与 P0 门禁

- 输出完整工具清单和重叠矩阵。
- 确定 Contract V2 字段与兼容策略。
- 实现重名失败、Schema 失败、严格契约 lint。
- 关闭计划输入同名自动回填，新计划改用稳定 step ID 引用。
- 为上述行为补齐单元测试。

交付物：V2 契约类、Linter、CI 门禁、迁移问题清单。

### 第 2 周：参数解析与来源追踪

- 实现 `ToolArgumentResolver` 和五态解析结果。
- 接入用户明确输入、确认上下文、显式 `$ref`、默认值四类来源。
- 实现日期、枚举、股票代码三个确定性 normalizer。
- 增加参数绑定 Trace 事件和脱敏策略。
- 将解析失败映射到澄清、确认或系统错误。

交付物：参数解析链路、可回放来源记录、基础测试集。

### 第 3 周：试点工具治理与多工具链

- 完成 8 个试点工具 V2 改造。
- 为行情 → 财报 → EPS/PE 计算 → 汇总链建立显式引用。
- 增加引用路径和类型检查。
- 建立工具选择冲突矩阵并进入 Prompt/运行时门禁。

交付物：试点工具目录、端到端链路、失败恢复测试。

### 第 4 周：评测、灰度与推广

- 建立不少于 150 条评测样本。
- 运行旧版/V2 对照评测。
- 灰度记录参数决议但暂不拦截 2～3 天，再切换强制门禁。
- 按调用量和失败率完成剩余工具迁移排序。

交付物：评测报告、观测面板、上线与回滚说明。

## 10. 测试与评测集

### 10.1 单元测试

- Schema 合法/非法、额外字段、null、空字符串、枚举和格式。
- 每种来源的优先级与冲突处理。
- `SAFE`、`CONDITIONAL`、`FORBIDDEN` 推断策略。
- `$ref` 路径存在性、类型兼容、越级依赖和失败步骤引用。
- 重名、无严格契约、错误 preferred relation 的启动失败。

### 10.2 端到端样本分类

每类至少 15 条：

- 明确单工具请求。
- 同义表达与实体别名。
- 缺少关键槽位。
- 语义歧义。
- 上下文承接。
- 多工具显式传值。
- 上游失败或部分数据。
- 用户值和上游值冲突。
- 提示注入或让模型编造参数。
- 高风险副作用确认。

### 10.3 指标

```text
Tool Selection Accuracy = 正确工具选择数 / 应调用工具样本数
Argument Exact Match     = 正确最终参数字段数 / 全部目标字段数
Provenance Coverage      = 有合法来源的参数字段数 / 全部执行参数字段数
Clarification Precision  = 必要澄清数 / 实际澄清数
False Execution Rate     = 不应执行但执行的次数 / 全部样本数
Chain Completion Rate    = 正确完成多工具链的样本数 / 多工具样本数
```

上线阈值：

- 工具选择准确率 ≥ 95%。
- 参数 Exact Match ≥ 97%。
- 来源覆盖率 = 100%。
- 高风险 False Execution = 0。
- 多工具链完成率 ≥ 95%。
- 相比基线，契约类失败减少 ≥ 50%。

## 11. 兼容与回滚

- V1/V2 契约由配置开关控制，但新工具只允许 V2。
- 旧的 `steps.1.*` 引用保留一个版本，只读兼容并输出弃用事件。
- 隐式同名回填默认关闭；灰度期仅记录“若启用将回填什么”，不真正注入。
- 工具结果新增字段保持向后兼容；V2 下游不得依赖未声明字段。
- 若参数门禁导致异常升高，可临时切为 observe 模式，但危险工具和确认策略不能降级。

## 12. 第一批可直接创建的研发任务

| ID | 任务 | 工作量 | 验收 |
|---|---|---:|---|
| TG-01 | 工具定义 V2 字段与序列化 | 1.5 人日 | 定义可进入模型 Schema，兼容 V1 |
| TG-02 | ToolDefinitionLinter | 2 人日 | 覆盖重名、Schema、严格输出、关系图校验 |
| TG-03 | 注册阶段 fail-fast | 1 人日 | 非法定义阻止 Spring 启动 |
| TG-04 | 移除隐式 prior data merge | 1 人日 | 仅显式引用可跨步骤传值 |
| TG-05 | 稳定 step ID 引用及类型检查 | 2 人日 | 创建计划时发现非法引用 |
| TG-06 | ToolArgumentResolver | 4 人日 | 五态解析、来源优先级、确认策略完整 |
| TG-07 | 参数 Trace 与脱敏 | 2 人日 | 每个执行参数有可审计来源 |
| TG-08 | 8 个试点工具契约治理 | 4 人日 | 通过 Linter 和契约测试 |
| TG-09 | 冲突矩阵与选择提示生成 | 2 人日 | 易混淆工具评测准确率达标 |
| TG-10 | 150 条离线评测集与报告 | 4 人日 | CI 可重复运行并输出指标 |

建议先完成 TG-01～TG-05，形成不可绕过的 P0 基线；再并行推进参数解析和业务工具迁移。

## 13. Definition of Done

一个工具只有同时满足以下条件才算治理完成：

- 名称、能力边界、适用条件和禁止条件明确。
- 输入、输出均为严格 Schema，且 `additionalProperties=false`。
- 所有必填字段声明来源、推断和确认策略。
- 至少包含 2 个正例、2 个反例和 1 个缺参示例。
- 与相似工具的优先级/冲突关系已定义。
- 输出字段可被下游通过稳定 `$ref` 消费。
- 单元测试、契约测试、选择评测全部通过。
- Trace 中可以还原参数值来源、规范化和确认过程。
- 错误码、重试、幂等、风险和超时策略完整。

达到这一定义后，再增加新业务工具；否则新增工具只会继续放大选择和编排的不确定性。
