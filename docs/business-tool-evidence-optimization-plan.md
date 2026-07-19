# StockMind 业务工具与证据能力优化计划

> 日期：2026-07-19  
> 阶段：下一阶段优化项  
> 范围：股票分析业务工具、数据覆盖、工具契约和金融语义约束；本阶段不重构 Agent 主流程。

## 1. 背景与结论

近期以“分析贵州茅台为什么上涨/下跌”为样例的运行日志表明，当前系统的框架主流程已经基本可用：

- 意图路由能够将归因问题识别为调查型任务；
- ReAct 能建立候选假设、选择工具并根据结果继续调查；
- 调查状态、失败重试、不可用工具阻断、停止判断和降级回答能够正常运行；
- 行情、成交量和 RSI 等工具可以自行查询底层数据，不需要依赖前序工具的输出；
- 工具失败时系统没有伪造事实，最终回答能够声明证据不足。

现阶段不需要优先重写 Agent 框架。主要瓶颈已经转移到业务能力层，按影响从高到低分为：

1. **业务数据和证据覆盖不足**：能够确认价格变化，但缺少完成原因归因所需的公告、真实新闻、财报、行业、基准、资金和渠道数据。
2. **工具契约和字段语义不够精细**：单日指标、区间指标、内部数据加载窗口和业务观察窗口容易混淆；形式成功与业务有效尚未分开。
3. **金融语义约束不足**：推理过程可能把技术状态当作原因证据，把搜索失败或低信息结果当作反证，把证据缺失错误表达为负面判断。

本轮优化目标是让系统从“能够描述股价发生了什么”升级到“能够基于可追溯证据解释股价为什么变化；证据不足时准确说明缺口”。

## 2. 设计原则

### 2.1 工具保持自包含

业务工具需要某类数据时，应通过自身依赖的领域服务查询，不依赖调用顺序，也不要求前序工具必须先执行。

推荐结构：

```text
stock_snapshot_analysis / stock_move_attribution
    ├── MarketDataProvider
    ├── TechnicalAnalysisService
    ├── NewsProvider
    ├── AnnouncementProvider
    ├── FundamentalDataProvider
    ├── BenchmarkDataProvider
    └── CapitalFlowProvider
```

禁止结构：

```text
historical_bars 必须先执行
    ↓
stock_snapshot_analysis 才能运行
```

多个工具需要相同数据时，通过统一 Provider 和请求级缓存复用，不通过工具之间直接传递私有输出实现复用。

缓存键至少包含：

```text
instrument + start_date + end_date + interval + adjustment + provider + as_of
```

### 2.2 运行状态负责推理和审计，不承担工具依赖注入

`investigation_state` 和执行日志继续保存：

- 已经调查过的维度；
- 经过验证的事实摘要；
- 原始证据引用和来源步骤；
- 业务时间、可靠性和数据质量；
- 工具失败、空结果和不可用状态；
- 尚未解决的关键缺口。

这些状态用于避免重复调查、更新假设、生成引用和决定是否停止，但不作为业务工具正常运行的必要前置条件。

### 2.3 事实、状态和原因严格分层

- **事实工具**回答价格、公告、财务和行业数据是什么；
- **状态分析工具**回答趋势、动量、波动和量价状态如何；
- **归因分析工具**比较事件窗口、板块、基准和公司证据，回答哪些原因得到支持；
- 技术指标不能单独升级为基本面、事件或资金原因。

### 2.4 缺少证据不等于反证

以下情况不得降低业务假设置信度：

- 工具超时或依赖不可用；
- 搜索结果为空但检索覆盖不充分；
- 返回内容符合 Schema 但没有实质信息；
- 某个数据维度暂未接入；
- 技术指标未越过经验阈值。

只有取得与假设直接矛盾、来源可靠且时间匹配的事实，才能登记为 `contradicting_evidence`。

## 3. 当前主要问题

### 3.1 原因归因所需数据覆盖不足

| 归因维度 | 当前状态 | 主要问题 | 目标能力 |
|---|---|---|---|
| 最新行情 | 已具备 | 单日与区间语义容易混用 | 明确最新交易日口径 |
| 历史 K 线 | 已具备 | 需要进一步识别关键上涨/下跌事件窗口 | 输出区间收益、回撤和关键交易日 |
| 技术指标 | 已具备基础能力 | 只能描述状态，不能单独解释原因 | 明确限制并增加区间量价分析 |
| 公司公告 | 缺失 | 无法验证公司事件 | 接入结构化公告和原文引用 |
| 新闻事件 | 低质量占位 | 形式成功但无有效内容 | 接入真实来源并评价结果质量 |
| 财务报告 | 不完整 | 依赖用户指定报告期 | 自动选择截至 `as_of` 最新已披露报告 |
| 市场基准 | 缺失 | 无法判断是否为市场普涨 | 提供沪深300、上证指数等同期收益 |
| 行业板块 | 缺失 | 无法识别白酒板块轮动 | 提供行业指数和同业同期表现 |
| 资金流向 | 缺失 | 成交量被错误替代为资金净流入 | 接入主力/大单/北向等可用资金数据 |
| 渠道数据 | 缺失 | 无法验证批价、库存和动销变化 | 接入有来源和时间戳的渠道指标 |
| 估值数据 | 缺失 | 无法区分盈利增长和估值扩张 | 提供 PE/PB、历史分位和一致预期 |

### 3.2 单日口径与区间口径仍可能混淆

`market_quote` 已将 `change_pct` 改为 `daily_change_pct`，但输出同时包含内部数据加载使用的 `start_at/end_at`。模型仍可能将单日涨跌幅描述成该日期范围的区间涨跌幅。

目标约束：

```text
daily_change_pct
    = as_of 当日收盘价相对前一交易日收盘价的涨跌幅

period_change_pct
    = start_date 至 end_date 首尾有效收盘价的区间涨跌幅
```

`market_quote` 建议输出：

```json
{
  "symbol": "600519",
  "as_of": "2026-07-17T07:00:00Z",
  "price": 540.62,
  "previous_close": 536.91,
  "daily_change_amount": 3.71,
  "daily_change_pct": 0.69,
  "volume": 6407225
}
```

内部预热或加载范围不作为业务字段暴露；确需保留时放入：

```json
{
  "metadata": {
    "history_loaded_from": "2026-06-19"
  }
}
```

确认多日上涨或下跌必须调用 `historical_bars`，并读取 `series_summary.period_change_pct`。

### 3.3 工具“成功”不代表取得有效业务证据

当前 `news_search` 可能返回占位摘要：

```text
市场关注度与相关事件摘要。
```

虽然符合输出 Schema，但不能用于支持或削弱任何业务假设。工具结果需要同时表达技术执行状态和业务结果质量。

建议统一增加：

```json
{
  "status": "success",
  "result_quality": "LOW_INFORMATION",
  "coverage": {
    "source_count": 0,
    "event_count": 0,
    "announcement_count": 0
  },
  "items": [],
  "limitations": [
    "当前结果不包含可用于事件归因的事实"
  ]
}
```

`result_quality` 建议枚举：

```text
HIGH_INFORMATION
PARTIAL_INFORMATION
LOW_INFORMATION
EMPTY_RESULT
NOT_APPLICABLE
```

技术失败继续通过 `status=failed` 和稳定 `error_code` 表达，不与业务空结果混用。

### 3.4 技术指标的结论边界不明确

当前推理中出现过以下不安全映射：

- 量比未极端放大，因此削弱资金驱动；
- RSI 未超过 70，因此削弱情绪驱动；
- 缺少基本面数据，因此上涨持续性存疑。

应在工具契约中明确：

- `volume_analysis` 描述量价状态，不等同于资金流向分析；
- `rsi_analysis` 描述价格动量，不直接识别市场情绪或上涨原因；
- RSI 70/30 是经验阈值，不是因果边界；
- 单个最新时点指标不能解释整个区间的价格变化；
- 缺少数据只能降低结论确定性，不能自动形成负面判断。

### 3.5 最新财报不应依赖用户指定报告期

对于“为什么上涨”“基本面有没有变化”等自然问题，系统应按 `symbol + as_of` 自动解析最新已披露报告期：

```text
latest_disclosed_period(symbol, as_of)
    ↓
financial_report_metrics(symbol, resolved_period)
```

只有用户明确要求特定报告期或多个报告期比较时，才需要用户提供期间。

## 4. 目标业务工具体系

### 4.1 基础事实工具

| 工具 | 核心职责 | 关键输出 |
|---|---|---|
| `market_quote` | 最新交易日行情 | price、previous_close、daily_change_pct、as_of |
| `historical_bars` | 指定窗口 OHLCV | bars、period_change_pct、drawdown、关键交易日 |
| `company_announcements` | 公司公告检索 | 公告类型、发布时间、原文引用、事件标签 |
| `news_search` | 新闻事件检索 | 真实来源、发布时间、摘要、事件实体、质量覆盖 |
| `latest_financial_report` | 自动解析并获取最新财报 | 报告期、披露日、营收利润现金流及同比环比 |
| `benchmark_performance` | 市场基准同期表现 | benchmark_return_pct、relative_return_pct |
| `sector_performance` | 行业和同业同期表现 | sector_return_pct、peer_distribution |
| `capital_flow` | 可用资金流向事实 | 净流入、口径、来源、时间窗口 |
| `valuation_snapshot` | 估值事实 | PE/PB、历史分位、一致预期 |
| `channel_data` | 渠道经营指标 | 批价、库存、动销、来源和观察时间 |

基础事实工具不得直接输出“上涨由某原因导致”的确定性结论。

### 4.2 专业分析工具

| 工具 | 目标 | 必要输入/内部查询 |
|---|---|---|
| `relative_performance_analysis` | 区分个股、行业和大盘因素 | 个股、行业、基准同期价格 |
| `event_window_analysis` | 检查事件前后异常收益和量价变化 | 公告/新闻事件和行情窗口 |
| `volume_price_analysis` | 分析整个区间量价结构 | OHLCV、OBV、上涨/下跌日量能 |
| `earnings_change_analysis` | 判断盈利变化是否支持估值重定价 | 最新及对比期财报、预期数据 |
| `valuation_change_analysis` | 区分盈利增长和估值扩张 | 价格、EPS、PE 历史序列 |
| `sector_attribution_analysis` | 判断板块轮动贡献 | 个股、行业、同业和市场基准 |

### 4.3 综合归因工具

新增或升级：

- `stock_move_attribution`：针对给定价格变化窗口，自行查询所需事实并输出候选驱动及证据强度；
- `stock_snapshot_analysis`：针对指定时点生成行情、技术、事件、基本面和估值快照，自行查询依赖数据，不要求先调用其他工具。

建议输出：

```json
{
  "status": "PARTIAL_SUCCESS",
  "confirmed_move": {
    "start_date": "2026-06-19",
    "end_date": "2026-07-17",
    "period_change_pct": 15.56
  },
  "candidate_drivers": [
    {
      "driver": "sector_recovery",
      "status": "supported",
      "confidence": 0.72,
      "evidence_refs": ["sector-1", "benchmark-1"]
    },
    {
      "driver": "company_event",
      "status": "insufficient_evidence",
      "confidence": null,
      "evidence_refs": []
    }
  ],
  "coverage": {
    "market": "complete",
    "sector": "complete",
    "news": "low_information",
    "fundamental": "unavailable"
  },
  "limitations": [
    "基本面数据不可用，因此不能判断盈利变化贡献"
  ]
}
```

## 5. Tool Contract V2 补充规范

在现有工具治理方案基础上，为金融业务工具补充以下字段：

```json
{
  "business_semantics": {
    "observation_scope": "LATEST_TRADING_DAY",
    "time_field": "as_of",
    "value_unit": "PERCENT",
    "causal_strength": "DESCRIPTIVE_ONLY"
  },
  "result_quality": "HIGH_INFORMATION",
  "coverage": {},
  "limitations": []
}
```

建议枚举：

### 5.1 观察范围

```text
LATEST_TRADING_DAY
REQUESTED_PERIOD
LATEST_DISCLOSED_REPORT
EVENT_WINDOW
POINT_IN_TIME
```

### 5.2 因果能力

```text
FACT_ONLY
DESCRIPTIVE_ONLY
CORRELATIONAL
ATTRIBUTION_SUPPORT
```

技术指标通常为 `DESCRIPTIVE_ONLY`；相对表现和事件窗口分析最多为 `ATTRIBUTION_SUPPORT`，不得宣称确定因果。

### 5.3 数据质量

每个时间敏感工具至少返回：

- `source`；
- `as_of` 或明确的 `start_date/end_date`；
- `timezone`；
- `adjustment`，如适用；
- `result_quality`；
- `coverage`；
- `limitations`；
- 可审计的 `evidence_refs`。

## 6. 推理层业务约束

在 Prompt 和/或 `ToolSemanticValidator` 中固化：

1. `daily_change_pct` 只能描述 `as_of` 对应的最新交易日。
2. 多日上涨或下跌必须引用 `period_change_pct`。
3. 工具失败、超时、依赖不可用和低信息结果不得作为业务反证。
4. RSI、MACD、量比和均线只能描述技术状态，不得单独确认上涨原因。
5. 成交量不能替代资金净流入。
6. 缺少证据只能输出“无法判断”，不得自动输出“持续性存疑”或“可能回调”。
7. 原因分析至少比较个股与一个市场基准；行业数据可用时必须同时比较行业。
8. 新闻和财报证据必须与确认后的价格变化窗口时间匹配。
9. 最新财报默认自动选择截至 `as_of` 已披露的最新期间。
10. 只有直接矛盾证据才能进入 `contradicting_evidence`。

对于“为什么上涨/下跌”，推荐默认调查顺序：

```text
确认价格变化窗口和幅度
    ↓
识别关键上涨/下跌交易日与回撤
    ↓
比较市场基准、行业和同业
    ↓
检索关键日期附近的公告和新闻
    ↓
读取截至窗口结束日最新财报和估值
    ↓
补充资金、量价和渠道证据
    ↓
按证据强度输出主因、次因和未决项
```

## 7. 实施优先级

### P0：修复口径和错误推断

1. `market_quote` 移除或下沉内部历史加载窗口。
2. 为 `daily_change_pct`、`period_change_pct` 增加机器可校验的观察范围。
3. `news_search` 增加 `result_quality/coverage`，占位内容不得视为有效证据。
4. 增加“失败或低信息结果不得作为反证”的语义校验。
5. 修改最终答案规则：证据缺失只能表达不确定性。
6. 最新财报自动解析报告期。

### P1：补齐归因所需核心证据

1. 接入真实公司公告和新闻来源。
2. 增加市场基准、白酒板块和同业表现工具。
3. 增加最新财报和估值工具。
4. 扩展历史行情摘要：区间收益、峰值回撤、关键涨跌日、区间量价特征。
5. 将技术工具描述调整为状态分析，明确非因果边界。

### P2：形成综合归因能力

1. 实现 `relative_performance_analysis`。
2. 实现 `event_window_analysis`。
3. 实现自包含的 `stock_move_attribution`。
4. 升级自包含的 `stock_snapshot_analysis`。
5. 接入资金流向、渠道数据和一致预期等增强证据。

## 8. 验收标准

### 8.1 行情口径

- `market_quote.daily_change_pct` 不得被最终回答描述为多日区间涨幅。
- 未取得 `period_change_pct` 时，不得声称某个日期范围累计上涨或下跌多少。
- 同一分析中行情、技术指标和基准使用一致的 `as_of`、复权模式和时区。

### 8.2 结果质量

- 新闻占位摘要被标记为 `LOW_INFORMATION`，不进入支持或反对证据。
- 工具超时和服务不可用只影响覆盖率，不改变业务假设方向。
- `success + LOW_INFORMATION` 与 `failed` 在日志、状态和最终答案中明确区分。

### 8.3 工具独立性

- `volume_analysis`、`rsi_analysis`、`stock_snapshot_analysis` 在没有前序行情工具调用时也能正常运行。
- 多工具使用相同查询口径时允许通过 Provider 缓存复用，但不依赖彼此输出结构。
- 调换无依赖工具的调用顺序不改变业务结果。

### 8.4 原因归因

- 最终归因结论至少包含两个独立证据维度，且每个结论均有 `evidence_refs`。
- 只取得价格和技术指标时，系统必须说明只能描述价格状态，不能确认原因。
- 缺少基本面数据时输出“无法判断基本面贡献”，不得输出“缺乏基本面支撑”。
- 无行业或基准数据时，不得判断上涨是个股独立行情或板块驱动。

### 8.5 回归样例

至少覆盖：

1. 单日上涨、近一个月下跌；验证单日和区间口径不会混淆。
2. 近一个月上涨、最新交易日回调；验证局部回撤不会否定区间趋势。
3. 新闻工具返回低信息占位内容；验证假设置信度不下降。
4. 新闻工具超时；验证失败不会成为反证。
5. 用户未指定财报期间；验证自动选择最新已披露报告。
6. 个股与板块同步上涨；验证板块因素得到支持。
7. 个股显著跑赢板块且存在公告；验证公司事件进入主要候选原因。
8. 只有 RSI 和量比数据；验证最终答案不产生无证据因果推断。
9. 不预先调用行情工具，直接调用 `stock_snapshot_analysis`；验证其能够自行查询依赖。

## 9. 里程碑建议

### 里程碑 A：语义止损

- 完成 P0 全部内容；
- 建立上述回归样例；
- 消除单日/区间口径混用和失败即反证问题。

### 里程碑 B：证据补全

- 接入公告、真实新闻、基准、行业和最新财报；
- 原因分析能够区分市场、板块和公司特异性因素。

### 里程碑 C：综合归因

- 上线事件窗口和相对表现分析；
- `stock_move_attribution` 能输出候选驱动、证据强度、覆盖率和局限；
- 综合工具保持自包含，并通过 Provider/缓存实现数据复用。

## 10. 本阶段非目标

- 不重写意图路由、ReAct 主循环和工具执行中间件；
- 不让工具直接读取其他工具的私有输出；
- 不把技术指标升级为确定性交易建议；
- 不在缺少来源和时间口径的情况下增加更多推测性字段；
- 不以增加工具数量替代真实数据质量和工具契约治理。

本计划完成后，再根据回归日志判断是否需要调整 ReAct 轮次、停止策略或调查状态结构。框架调整应由明确的业务工具证据证明，而不是优先假设框架存在问题。
