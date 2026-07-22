# StockMind 股票因子分析能力可执行方案

> 文档状态：阶段0、阶段1、阶段2已完成，阶段3待执行  
> 制定日期：2026-07-20  
> 最近更新：2026-07-21  
> 目标：将股票分析从“查询工具返回数据后由模型做简易判断”升级为“业务代码确定性计算因子，模型基于可审计结果做综合解释”  
> 强约束：本方案只改 StockMind 业务模块，不修改 Agent 框架层

## 1. 执行结论

下一阶段不继续堆叠零散查询工具，而是在业务模块内建设统一的股票因子分析能力：

```text
真实数据 Provider
    ↓
Point-in-Time 数据快照
    ↓
确定性因子计算器
    ↓
因子质量校验、行业比较与风险标记
    ↓
单股因子画像 / 全市场筛选 / 情景估值
    ↓
Agent 模型解释结果并生成报告
```

业务代码负责：

- 代码与市场规范化；
- `as_of` 时点一致性；
- 财务口径匹配；
- 因子公式计算；
- 异常值、缺失值和样本质量处理；
- 行业内比较和评分；
- 风险标记和计算过程审计。

模型只负责：

- 根据用户问题选择分析期限和投资风格；
- 解释估值、质量、成长、预期、动量和股东回报之间的矛盾；
- 结合公告、新闻和行业事实形成结论；
- 给出结论适用范围、失效条件和风险提示。

模型不得再自行完成 PE、增长率、分位数、综合分数等确定性计算，也不得凭印象从全市场选择候选股票。

## 2. 改动边界

### 2.1 允许修改的业务模块

| 模块 | 允许改动 | 主要职责 |
|---|---|---|
| `stockmind-domain` | 是 | 因子值、评分、快照、风险标记等业务模型 |
| `stockmind-application` | 是 | 数据快照编排、因子计算器、筛选与估值服务 |
| `stockmind-infrastructure` | 是 | 腾讯、新浪、东方财富等数据适配及 point-in-time 查询实现 |
| `stockmind-bootstrap` | 仅薄适配 | 新业务工具暴露、Bean 装配、股票业务 Prompt 补充 |
| `stockmind-common` | 原则上不改 | 仅当存在真正跨业务的通用值对象时另行评审 |

### 2.2 禁止修改的框架模块

本阶段不得修改：

- `javascope-agent`
- `javascope-agent-context`
- `javascope-agent-core`
- `javascope-agent-model-openai`
- `javascope-agent-spi`
- `javascope-agent-spring-boot-starter`
- `javascope-user`

不得调整以下框架能力：

- ReAct 主循环；
- 路由协议；
- 工具调用协议；
- 通用调查状态 Schema；
- 框架重试和超时机制；
- 模型客户端和通用 Prompt 结构。

`stockmind-bootstrap` 中新增代码必须保持为薄适配层，不得在那里堆积因子公式或数据清洗逻辑。

### 2.3 边界验收

当前工作区可能已经存在此前任务产生的未提交框架改动，因此不能简单要求`git diff`全局为空。开始阶段0前先记录框架差异基线：

```bash
git diff -- 'javascope-*' 'javascope-user' | shasum -a 256
git diff --name-only -- 'javascope-*' 'javascope-user'
```

每个实施批次完成后重复执行。期望哈希和文件列表与阶段0开始前的基线完全一致，且本批次变更清单中不包含任何框架文件。若框架差异发生变化，当前批次不得合并，必须将本批次实现移回业务模块。既有框架差异只作为工作区基线保留，本方案不得继续修改。

## 3. 当前能力与主要缺口

目前已经具备：

- 腾讯行情、K线、PE TTM、PB和市值；
- 新浪结构化三张财务报表；
- 东方财富研报及EPS预测；
- 东方财富分红数据；
- 行业板块及前10大市值成分股；
- 公司公告、新闻、行情相对表现等查询工具。

当前核心缺口不是“查询不到数据”，而是：

1. 不同工具的价格和财务数据可能不在同一 `as_of`；
2. 股票与指数代码存在歧义；
3. 原始财务值未形成盈利质量、增长质量和现金流质量判断；
4. 一致预期只有当前值，没有预测修正方向；
5. 估值只看当前值，没有历史分位和可比口径；
6. 推荐股票时没有全市场候选池和确定性排序；
7. 模型容易把“低PE”直接解释为低估，把“利润大增”直接解释为主营增长；
8. 工具虽然成功，但不能说明因子覆盖是否足以支持结论。

## 4. 总体设计

### 4.1 业务组件划分

建议新增以下业务包：

```text
stockmind-domain
└── com.stockmind.domain.factor
    ├── FactorCategory
    ├── FactorValue
    ├── FactorScore
    ├── FactorQuality
    ├── FactorWarning
    ├── StockFactorProfile
    ├── FactorCoverage
    └── ScenarioValuation

stockmind-application
└── com.stockmind.application.factor
    ├── PointInTimeStockSnapshotService
    ├── StockFactorProfileService
    ├── FactorCalculator
    ├── ValuationFactorCalculator
    ├── QualityFactorCalculator
    ├── GrowthFactorCalculator
    ├── ExpectationRevisionFactorCalculator
    ├── MomentumRiskFactorCalculator
    ├── ShareholderReturnFactorCalculator
    ├── FactorNormalizationService
    ├── ScenarioValuationService
    └── screening
        ├── AShareFactorScreeningService
        ├── ScreeningPolicy
        └── ScreeningResult

stockmind-infrastructure
└── 继续使用现有 provider 包，并按需要补充 point-in-time 数据查询

stockmind-bootstrap
└── FactorAnalysisTools.java
```

因子计算器不得依赖 Agent、工具注解或执行日志。它们应当是可以直接通过单元测试调用的普通业务服务。

### 4.2 数据流

```text
symbol + as_of
    ↓
InstrumentResolver
    ↓
PointInTimeStockSnapshotService
    ├── MarketDataProvider
    ├── FinancialReportProvider
    ├── AnalystResearchProvider
    ├── DividendProvider
    └── SectorDataProvider
    ↓
PointInTimeStockSnapshot
    ↓
六类 FactorCalculator
    ↓
FactorNormalizationService
    ↓
StockFactorProfile
```

同一个因子画像必须共享同一份请求级快照，禁止各计算器分别获取“当前行情”，否则会再次产生时点和价格不一致。

## 5. 前置正确性门禁

因子建设开始前，先完成以下业务正确性改造。

### 5.1 股票和指数代码规范化

新增业务值对象或解析服务，内部统一使用：

```text
SZ000001  平安银行
SH000001  上证指数
SH600519  贵州茅台
SZ000858  五粮液
```

规则：

- 六位代码只能在明确为“股票”时按交易所规则补全；
- 指数必须带市场和资产类型，不允许以裸 `000001` 表示上证指数；
- Provider 请求前完成标准化；
- 返回结果必须包含 `instrument_type`、`exchange`、`normalized_symbol` 和 `name`；
- 发现输入歧义时返回稳定业务错误，不由模型猜测。

### 5.2 `as_of` 时点一致性

现有 `MarketDataProvider.loadQuote(symbol)` 只能表达实时行情。业务层需要增加 point-in-time 语义，但不能修改框架协议。

建议业务接口：

```java
MarketPriceSnapshot loadPrice(String symbol, LocalDate asOf);
```

实现规则：

- `as_of` 为当前交易日且实时行情有效：使用实时报价；
- `as_of` 为历史日期：从前复权日K中选择不晚于该日期的最后交易日收盘价；
- 历史日期禁止回退为当前实时价格；
- 输出 `requested_as_of`、`effective_trade_date` 和 `price_source_type`；
- 前向PE、股息率和市值类因子必须使用同一快照价格。

### 5.3 财务 point-in-time 过滤

纳入因子计算的财报必须满足：

```text
published_date <= as_of
```

同时保留：

- `report_period`：报告期；
- `initial_published_date`：首次披露日期；
- `latest_updated_date`：最新更新或重述日期；
- `statement_scope`：单季度、累计季度、半年度、年度；
- `restated`：是否重述。

历史分析不得读取 `as_of` 之后披露的财报和研报。

### 5.4 统一单位和口径

业务快照统一使用：

- 金额：CNY 元；
- 比率：内部使用小数，工具输出统一为百分比字段时加 `_pct`；
- 成交量：明确股或手；
- 市值：内部使用元，展示层可输出亿元；
- PE：TTM和Forward分开；
- EPS：报告EPS、TTM EPS和机构预测EPS分开；
- 财务同比：使用数据源同比值或同口径报告期计算，不混用累计期环比。

## 6. 单股因子画像 MVP

第一版实现六类因子，但区分 MVP 可交付项与后续增强项。

### 6.1 估值因子

#### MVP 指标

| 指标 | 公式/来源 | 方向 |
|---|---|---|
| `pe_ttm` | 腾讯行情 | 越低通常越优，亏损时不评分 |
| `pb` | 腾讯行情 | 行业内比较 |
| `earnings_yield_pct` | `100 / PE_TTM` | 越高越优 |
| `peer_pe_premium_pct` | `(标的PE / 同行PE中位数 - 1) × 100` | 越低越优 |
| `peer_pb_premium_pct` | `(标的PB / 同行PB中位数 - 1) × 100` | 越低越优 |
| `dividend_yield_pct` | TTM每股现金分红/同时点价格 | 越高越优 |
| `forward_pe` | 同时点价格/年度预测EPS中位数 | 行业内同口径比较 |
| `forecast_eps_cagr_pct` | 最新年度至第三年度预测EPS CAGR | 越高越优 |

#### 增强指标

- 3年、5年PE历史分位；
- 3年、5年PB历史分位；
- EV/EBITDA；
- 自由现金流收益率；
- PEG；
- 反向DCF隐含增长率。

严禁：

- 用单季度EPS直接计算年度PE；
- 用标的Forward PE与同行TTM PE直接比较；
- 因为PE低就直接输出“低估”；
- 在同行中位数中把标的本身称为“纯同行样本”。

### 6.2 盈利质量因子

#### MVP 指标

| 指标 | 公式 |
|---|---|
| `net_margin_pct` | 归母净利润/营业收入 |
| `cash_to_net_profit_ratio` | 经营现金流/归母净利润 |
| `liability_to_asset_pct` | 总负债/总资产 |
| `cash_flow_gap_pct` | `(净利润-经营现金流)/abs(净利润) × 100` |
| `revenue_profit_growth_gap_pct` | 净利润同比-收入同比 |
| `margin_change_pct` | 最新同口径净利率-上年同期净利率 |

#### 自动风险标记

```text
PROFIT_GROWTH_DIVERGES_FROM_REVENUE
CASH_CONVERSION_WEAK
MARGIN_EXPANSION_REQUIRES_EXPLANATION
LEVERAGE_RISING
FINANCIAL_SCOPE_NOT_COMPARABLE
```

建议初始阈值：

- 净利润增速比收入增速高30个百分点以上：标记增长背离；
- 经营现金流/净利润小于0.7：标记现金转化偏弱；
- 连续两个同口径期间现金转化小于0.7：提升风险等级；
- 净利率单期变化超过10个百分点：要求检查非经常性损益。

阈值必须作为业务配置常量并写入结果，不得隐藏在 Prompt 中。

#### 增强指标

- 扣非净利润和扣非增速；
- 非经常性损益占比；
- ROE、ROIC；
- 应收账款、存货和收入增速差；
- 自由现金流；
- 商誉/净资产；
- 资本开支回报。

### 6.3 成长因子

#### MVP 指标

- 最新年度营收同比；
- 最新年度净利润同比；
- 最新季度营收同比；
- 最新季度净利润同比；
- 最近可比期间增速变化；
- 未来2年和3年EPS CAGR；
- 增长状态枚举。

增长状态：

```text
ACCELERATING
STABLE
DECELERATING
RECOVERING
TURNING_NEGATIVE
INSUFFICIENT_DATA
```

季度与年度累计数据不得直接环比。增长加速度只能比较同口径同比值，例如Q1同比与上一年度Q1同比，或者连续年度同比。

### 6.4 机构预期修正因子

#### MVP 指标

- 当前年度EPS中位数；
- 30天前EPS中位数；
- 90天前EPS中位数；
- 30天EPS修正率；
- 90天EPS修正率；
- 上调机构数；
- 下调机构数；
- 预测离散度；
- 评级分布；
- 有效机构数。

公式：

```text
EPS修正率 = 当前一致预期EPS / 历史一致预期EPS - 1
预测离散度 = EPS预测标准差 / abs(EPS预测均值)
```

输出状态：

```text
STRONGLY_UPGRADED
UPGRADED
STABLE
DOWNGRADED
STRONGLY_DOWNGRADED
LOW_SAMPLE
```

机构报告只代表观点。少于3家机构时不生成方向性评分，只返回原始数据和`LOW_SAMPLE`。

### 6.5 动量与风险因子

使用同一腾讯前复权日K数据确定性计算：

- 20、60、120、250交易日收益率；
- 相对沪深300超额收益；
- 相对行业指数超额收益；
- 20日和60日年化波动率；
- 120日和250日最大回撤；
- 60日Beta；
- 均线状态；
- 上涨日/下跌日成交量比；
- 最近20日流动性。

趋势状态：

```text
STRONG_UPTREND
UPTREND
RANGE_BOUND
DOWNTREND
STRONG_DOWNTREND
```

动量因子只能用于描述价格状态和买入时点风险，不能证明公司基本面改善或事件原因。

### 6.6 股东回报因子

#### MVP 指标

- TTM每股现金分红；
- TTM股息率；
- 最近3年每股分红；
- 3年分红CAGR；
- 连续分红年数；
- 分红稳定性；
- 分红覆盖状态。

增强阶段增加：

- 回购收益率；
- 增发稀释率；
- 净回购收益率；
- 股东总收益率。

## 7. 因子质量、标准化与评分

### 7.1 每个因子必须携带的审计字段

```json
{
  "name": "cash_to_net_profit_ratio",
  "raw_value": 0.773,
  "normalized_value": 0.773,
  "unit": "ratio",
  "direction": "HIGHER_IS_BETTER",
  "as_of": "2026-07-20",
  "report_period": "2026-03-31",
  "source": ["sina_financial_api"],
  "formula": "operating_cash_flow / attributable_net_profit",
  "quality": "VALID",
  "warnings": []
}
```

模型只能引用`quality=VALID`或明确说明限制后的`PARTIAL`因子。

### 7.2 数据质量枚举

```text
VALID
PARTIAL
LOW_SAMPLE
STALE
NOT_COMPARABLE
MISSING
INVALID
```

### 7.3 行业标准化

当有效同行样本不少于8只时：

1. 排除缺失值；
2. 对亏损PE等无意义数据不评分；
3. 使用行业分位数作为0～100分；
4. 根据因子方向转换分数；
5. 同时保留原始值、中位数和样本数。

后续全市场数据充足时增加1%～99%缩尾和行业Z-score。MVP不在10只样本上使用复杂正态化。

### 7.4 分类评分

初始默认权重：

| 类别 | 权重 |
|---|---:|
| 估值 | 25% |
| 盈利质量 | 25% |
| 成长 | 20% |
| 预期修正 | 15% |
| 动量与风险 | 10% |
| 股东回报 | 5% |

权重不是投资结论，只是候选排序规则。必须输出各分项得分，不允许只返回综合分。

### 7.5 覆盖率门禁

```text
coverage = 有效权重之和 / 配置权重总和
```

- 覆盖率不低于80%：允许输出综合评分；
- 60%～80%：允许输出临时评分，但标记`PARTIAL`；
- 低于60%：不输出综合评分，只输出已计算因子；
- 任一分类不得用其他分类重复补权；
- PE、盈利收益率和Forward PE高度相关，分类内部先聚合，避免重复计权。

### 7.6 投资风格配置

第一版支持：

```text
QUALITY_VALUE
BALANCED
GROWTH
DEFENSIVE_INCOME
```

风格仅改变业务评分权重，不改变原始因子值。每次输出必须携带使用的权重版本，例如：

```text
factor_policy_version = stock-factor-v1
```

## 8. 对外业务工具设计

### 8.1 `stock_factor_profile`

用途：生成单只股票完整因子画像，是后续“是否具有投资价值”的主要定量工具。

输入：

```json
{
  "symbol": "SH603259",
  "as_of": "2026-07-20",
  "style": "BALANCED"
}
```

输出主要结构：

```json
{
  "symbol": "SH603259",
  "name": "药明康德",
  "as_of": "2026-07-20",
  "effective_trade_date": "2026-07-20",
  "policy_version": "stock-factor-v1",
  "coverage_pct": 88,
  "overall_score": 71,
  "categories": {
    "valuation": {},
    "quality": {},
    "growth": {},
    "expectation_revision": {},
    "momentum_risk": {},
    "shareholder_return": {}
  },
  "positive_signals": [],
  "negative_signals": [],
  "warnings": [],
  "limitations": [],
  "sources": []
}
```

工具只返回确定性计算结果和业务标签，不直接输出“建议买入/卖出”。

### 8.2 `scenario_valuation_analysis`

用途：基于下一财年机构EPS中位数和明确的Forward PE假设计算悲观、基准、乐观情景。

MVP采用PE情景法：

```text
目标价格 = 情景年度EPS × 情景目标PE
预期价格收益率 = 目标价格 / 当前价格 - 1
预期总收益率 = 预期价格收益率 + 持有期预计股息率
```

口径约束：

- TTM PE只与TTM盈利比较，Forward PE只与对应预测年度EPS组合；
- 同行TTM PE不得直接应用于下一财年机构EPS；
- 悲观/基准/乐观Forward PE齐全且由用户显式输入时，才输出命名情景；
- 未提供完整Forward PE假设时，只输出带明确EPS年度和PE口径的敏感性矩阵，不输出唯一“合理价格”。

### 8.3 `a_share_factor_screen`

用途：对A股候选池做确定性筛选。该工具放在第三阶段实现。

输入示例：

```json
{
  "as_of": "2026-07-20",
  "style": "QUALITY_VALUE",
  "exchanges": ["SH", "SZ"],
  "top_n": 20,
  "max_per_industry": 2
}
```

基础过滤：

- 排除ST、*ST和退市整理；
- 排除停牌；
- 排除上市不足250个交易日；
- 排除近20日流动性不足；
- 排除关键财务数据缺失；
- 默认不排除亏损公司，但估值类因子不评分并降低覆盖率；
- 记录每只股票被排除的确定性原因。

筛选结果不是最终推荐。排名前20只还要经过公告、新闻和重大风险检查，再由模型形成5～10只候选建议。

## 9. 模型使用规则

股票投资价值问题优先调用`stock_factor_profile`，不再要求模型依次手工拼接六个查询工具。

模型生成结论时必须区分：

1. **事实**：接口返回的行情、财务、公告和研报；
2. **计算结果**：因子值、分位数、评分和风险标记；
3. **机构观点**：券商预测、评级和目标价；
4. **模型判断**：综合结论和情景解释。

模型不得：

- 覆盖业务计算器返回的分数；
- 自行修改因子权重；
- 将缺失因子视为负面因子；
- 将研报评级当作高可靠性事实；
- 把单日涨跌当作投资价值证据；
- 在覆盖率不足时输出强结论；
- 将股票筛选结果直接描述成确定性投资建议。

推荐类问题必须先调用全市场筛选工具。没有全市场筛选能力时，应明确只能分析用户指定标的，不得由模型凭印象推荐股票。

## 10. 分阶段实施计划

### 阶段0：正确性门禁

任务：

- [x] `F0-01`：实现股票/指数规范化和歧义检查；
- [x] `F0-02`：实现历史价格`as_of`解析，消除实时价格穿越；
- [x] `F0-03`：统一财报披露日期、报告期和报表范围；
- [x] `F0-04`：统一金额、比率、成交量和市值单位；
- [x] `F0-05`：修正Forward PE与TTM PE比较规则；
- [x] `F0-06`：建立请求级`PointInTimeStockSnapshot`。
- [x] `F0-07`：建立外部数据端点登记、准入测试和数据源降级规则。

状态说明：`[x]`已完成并通过当前批次固定样本验收；`[~]`已开始但尚未满足完整退出标准；`[ ]`未开始。

#### 阶段0执行记录（2026-07-20，批次1）

- `F0-01`：新增`Instrument`、`Exchange`、`InstrumentType`和`InstrumentResolver`。裸6位代码在未声明资产类型时返回稳定歧义错误；已覆盖`SZ000001`股票、`SH000001`指数和后缀格式。
- `F0-02`：新增`MarketPriceSnapshot`和`PointInTimeStockSnapshotService`。历史`as_of`只读取不晚于请求日期的前复权日K收盘价，实时行情日期不匹配时不进入历史结果。
- `F0-03`：`FinancialStatementPeriod`已保留报告期、首次披露日期、最新更新日期、报表范围和重述标记；快照同时过滤未来披露、未来更新财报及未来研报。新浪财报缺少披露日期时不再用报告期伪造披露日期。
- `F0-04`（部分）：已在快照边界将行情成交量从手转换为股、市值从亿元转换为元，并提供百分比转小数的显式转换；财务字段的逐字段金额/比率单位契约仍待完成。
- `F0-05`：新增`ValuationMetricBasis`，只有相同估值口径允许直接比较，固定样本已验证Forward PE与TTM PE不可比较。
- `F0-06`（部分）：已建立包含价格、420日历日K线窗口、财报、研报、分红、未来数据过滤计数和Provider失败信息的不可变请求级快照；同行/行业数据尚未纳入同一快照。
- `F0-07`（部分）：已建立外部数据集登记、`POINT_IN_TIME/CURRENT_ONLY/UNSUPPORTED`时点能力、准入状态及默认降级策略；东方财富统一访问门、端点存活/字段契约测试尚未完成。
- 验证：JDK 21编译通过；`PointInTimeStockSnapshotAcceptanceTest`和既有`MarketSeriesSummarizerAcceptanceTest`通过；`git diff --check`通过。
- 框架边界：执行前后框架差异哈希均为`3deb1e8039cc15203eff32d9e52b0bf96c18229e846982dc691af47e84624104`，文件列表未变化。

#### 阶段0执行记录（2026-07-20，批次2）

- `F0-04`：新增财务字段单位契约和规范化模型。财务金额统一为CNY元，同比及百分比统一为内部小数，每股值、股数和天数使用独立单位；实时成交量、成交额、换手率和市值分别从手、万元、百分比和亿元转换为股、元、小数和元。
- `F0-06`：统一快照现包含价格、K线窗口、规范化财报、研报、分红及行业同行集合，并已完成业务Bean装配。当前同行数据每个请求只加载一次；行业源为`CURRENT_ONLY`，历史`as_of`明确返回不可用限制而不读取当前同行数据；Provider失败记录在快照中并降低后续覆盖率，不导致其他数据集失败。
- `F0-07`：新增外部端点准入证据检查和确定性失败原因；四个东方财富Provider已统一接入共享访问门，实现按host串行、最小请求间隔、GET缓存、403熔断和429/5xx退避。现有Provider的固定样本契约测试全部通过。
- 验证：JDK 21业务代码和基础设施代码编译通过；point-in-time快照、端点准入、东方财富访问门、腾讯行情、新浪财报、东方财富研报/分红/行业/新闻及巨潮公告固定样本验收通过；`git diff --check`通过。
- 框架边界：批次2完成后框架差异哈希仍为`3deb1e8039cc15203eff32d9e52b0bf96c18229e846982dc691af47e84624104`，文件列表未变化。

- [x] 阶段0退出标准：后续因子计算所需数据由统一请求级快照提供；历史日期不会读取未来行情、财报、研报、分红或当前同行数据。

### 阶段1：单股因子画像 MVP

任务：

- [x] `F1-01`：新增因子领域模型和数据质量枚举；
- [x] `F1-02`：实现估值因子计算器；
- [x] `F1-03`：实现盈利质量因子计算器；
- [x] `F1-04`：实现成长因子计算器；
- [x] `F1-05`：实现机构预期修正计算器；
- [x] `F1-06`：实现动量与风险计算器；
- [x] `F1-07`：实现股东回报计算器；
- [x] `F1-08`：实现覆盖率和分类评分；
- [x] `F1-09`：实现`StockFactorProfileService`；
- [x] `F1-10`：在`stockmind-bootstrap`薄封装`stock_factor_profile`工具；
- [x] `F1-11`：更新股票业务Prompt，要求优先使用因子画像。

#### 阶段1执行记录（2026-07-20）

- 新增估值、盈利质量、成长、机构预期修正、动量风险和股东回报六类普通业务计算器；计算器仅依赖统一`PointInTimeStockSnapshot`，不依赖Agent或工具协议。
- 每个因子输出原始值、规范值、单位、方向、`as_of`、报告期、来源、公式、质量、警告和限制；少于3家机构时预期因子标记`LOW_SAMPLE`且不进入方向性评分。
- 实现`stock-factor-v1`四种风格权重、分类评分和覆盖率门禁：覆盖率低于60%不输出综合评分，60%～80%输出`PARTIAL`评分，不跨分类补权。
- 同行估值使用排除标的后的纯同行TTM PE/PB样本；当前同行行情在请求级快照中只加载一次，历史请求不读取当前同行数据。
- 新增`stock_factor_profile`薄适配工具，返回六类因子、覆盖率、分项评分、风险标记、限制和来源，不返回买卖建议；Prompt已要求投资价值问题优先使用画像且禁止模型重算分数。
- 固定样本已覆盖：药明康德利润/收入增速背离及现金转化风险、五粮液未来EPS下降状态、贵州茅台纯同行样本口径、历史`as_of`无实时数据穿越、六类工具JSON输出。
- 验证：JDK 21业务、基础设施和Bootstrap相关代码编译通过；因子计算器、快照、工具调用、工具Schema及腾讯/新浪/东财/巨潮Provider固定样本回归通过；`git diff --check`通过。
- 框架边界：阶段1完成后框架差异哈希仍与阶段0基线一致，未新增或修改任何框架文件。

- [x] 阶段1退出标准：指定股票和`as_of`可以返回六类因子、覆盖率、分项评分、风险标记、公式与来源。

### 阶段2：深度估值与预期差

任务：

- [x] `F2-01`：历史PE/PB序列及3年、5年分位；
- [x] `F2-02`：扣非利润、非经常性损益和自由现金流；
- [x] `F2-03`：ROE、ROIC和营运资本质量；
- [x] `F2-04`：30/90/180天机构预测修正；
- [x] `F2-05`：业绩超预期/低于预期分析；
- [x] `F2-06`：PE情景估值和敏感性矩阵；
- [x] `F2-07`：实现`scenario_valuation_analysis`工具。
- [x] `F2-08`：接入解禁、两融、股东户数、大宗交易等补充风险因子。

#### 阶段2执行记录（2026-07-20）

- 历史估值使用6年行情窗口及各交易日当时已披露财务数据重建TTM EPS和BVPS，计算3年、5年PE/PB经验分位；3年少于500个、5年少于900个有效样本时返回`MISSING`，不伪造历史分位。
- 盈利质量新增扣非利润、非经常性损益占比、自由现金流、ROE、ROIC、应收/存货与收入增速差、商誉/净资产；缺失字段和不可比期间不参与评分。
- 机构预期修正扩展到30/90/180天，并按报告期年度实际EPS与披露前一致预期计算业绩超预期/低于预期；少于3个样本保持`LOW_SAMPLE`。
- 新增PE情景估值服务及`scenario_valuation_analysis`工具，输出悲观/基准/乐观情景、价格收益率、含股息总收益率和敏感性矩阵。修正后下一财年EPS只与Forward PE假设组合；完整用户PE假设可生成命名情景，否则只返回敏感性矩阵。
- 新增东方财富补充风险Provider，接入未来30/90日解禁比例、20日融资余额变化、股东户数变化和大宗交易平均折价；数据为`CURRENT_ONLY`，历史画像不读取当前风险数据。
- 端点准入存活检查已使用`600519`、`000858`、`603259`三只沪深股票完成：解禁、两融、股东户数和大宗交易端点HTTP及结构校验通过；无解禁记录按空数据处理。两融端点已独立确认使用`SCODE`过滤字段，其余三类使用`SECURITY_CODE`。
- 补充风险只进入`momentum_risk`分类和警告，能够触发`UPCOMING_LOCKUP_RELEASE`、`MARGIN_CROWDING_RISING`、`SHAREHOLDER_DISPERSION_RISING`和`BLOCK_TRADE_DISCOUNT`，不改变估值、质量和成长原始因子。
- 固定样本验证：5年point-in-time估值分位、FCF/ROE、180天EPS修正、四类风险警告、同行充足的三情景估值、同行不足时仅返回3×3敏感性矩阵，以及风险Provider字段/单位契约。
- 验证：JDK 21业务、基础设施和Bootstrap相关代码编译通过；阶段0/1回归、阶段2因子、风险Provider、工具调用和工具Schema验收通过；`git diff --check`通过。
- 框架边界：阶段2完成后框架差异哈希与阶段0基线一致，本阶段未修改任何框架文件。

#### 阶段0～2注释与业务Prompt复核（2026-07-20）

- [x] 为快照、六类因子计算、覆盖率评分、历史/情景估值、外部数据准入、东财访问门和工具适配层的新增公开方法补充职责、时点口径及降级边界注释。
- [x] 重构股票业务Action/Plan/Validation Prompt：统一为“综合价值先`stock_factor_profile`，合理价再`scenario_valuation_analysis`”，移除旧估值工具链的竞争优先级，并固化覆盖率、证据分层、因果、情景估值与全市场推荐门禁。
- [x] 新增`StockBusinessPromptAcceptanceTest`，并通过JDK 21编译、阶段2因子回归、快照回归、因子/情景工具验收、Prompt关键规则和工具Schema验收。
- [x] 框架差异哈希仍为`3deb1e8039cc15203eff32d9e52b0bf96c18229e846982dc691af47e84624104`，本次未修改Agent框架文件。

#### 阶段2工具口径修复（2026-07-20）

- [x] 修正新浪`item_tongbi`小数比例在快照规范化层被重复除以100的问题；成长因子统一为`raw_value=展示百分数`、`normalized_value=内部小数`。
- [x] `stock_factor_profile`对TTM PE、Forward PE、PB及其分位/同行比较增加机器可读口径字段。
- [x] `scenario_valuation_analysis`显式返回当前年度Forward PE、情景EPS预测年度、EPS口径、目标PE口径及TTM/Forward不可比标记；移除“下一年度EPS×同行TTM PE”混用。

#### 最新日志问题修复：工具口径与业务语义（2026-07-20）

- [x] 新增共享`AnalystForecastConsensus`，按“研报发布日期对应预测基准年 + 目标财年”对EPS预测归年，并且每家机构只保留截止时点的最新有效预测；估值因子、预期修正、机构一致预期工具和情景估值统一使用该口径。
- [x] 机构预测修正固定比较同一目标财年，避免把旧研报的“下一年预测”和新研报的“当年预测”拆成不同年度或混为不同指标。
- [x] 成长加速/减速只比较上一年度同月同日、同`statement_scope`报告期的同比值；缺少可比期时返回`MISSING/INSUFFICIENT_DATA`，不再用最近任意累计季度代替。
- [x] `stock_factor_profile`新增顶层`key_metrics`与`semantic_guardrails`，直接暴露3年/5年估值分位、年度/季度增长、可比增速变化、预期修正和同行估值状态，避免有效指标被深层结构遗漏。
- [x] 正负信号按业务状态重新分类；评级分布、分红明细等描述性结构不再自动作为正面信号，`DECELERATING`、`TURNING_NEGATIVE`、`DOWNGRADED`和`DOWNTREND`进入负面信号。
- [x] 同行估值数据不可用改为确定性、不可重试的业务错误，避免相同缺失条件触发工具层重复重试；未对通用工具调用次数或Agent框架增加限制。
- [x] 股票业务Prompt补充语义门禁：评级属于机构观点；公告标题不能推断动机或管理层信心；价格与动量不能提高内在价值置信度；增长加速必须有同报告期证据；同一预测年度跨工具冲突必须显式披露。
- [x] 新增`BusinessSemanticRegressionAcceptanceTest`并扩展因子工具与Prompt验收；JDK 21直接编译及业务、工具、Schema回归通过，全程未使用Maven。

#### 画像与同行估值可用性修复（2026-07-21）

- [x] 定位`stock_factor_profile`真实异常：3年PE历史样本达到数量门槛、但当前PE分位为空时仍被标记为`VALID`，评分器对空规范值调用`doubleValue()`导致空指针。
- [x] 历史PE/PB分位质量改为同时检查“当前分位非空”和“历史样本达到门槛”；`FactorValue.usable()`增加规范值非空约束，防止任何上游误标质量的空值进入评分。
- [x] 当前请求处于盘前或非交易日、实时接口仍返回最近交易日行情时，新增`LATEST_AVAILABLE_QUOTE`口径；仅当行情日期与K线最新有效交易日一致时保留PE/PB，避免错误降级为无估值字段的历史收盘价。
- [x] 将东方财富行业成分股默认端点从持续断开连接的`push2.eastmoney.com`切换到同结构且当前可用的`push2delay.eastmoney.com`，恢复`peer_valuation_comparison`。
- [x] `stock_factor_profile`异常增加服务端类型和消息日志，工具错误改为不可自动重试，避免相同确定性计算异常连续执行3次。
- [x] 固定样本回归通过；真实药明康德冒烟测试中画像返回成功、覆盖率86.29%、TTM PE 18.41且质量为`VALID`，同行估值返回10个有效PE/PB样本并成功完成比较。

#### 搜索相关性与同行稳定性修复（2026-07-21）

- [x] 确认日志中的搜索入参正确；问题来自业务时间窗口将明确的2025年历史范围改写为最近30天，以及东财搜索宽泛匹配后缺少本地相关性门槛。
- [x] 显式`start_date/end_date`或`time_window`历史范围现在原样保留；只有未提供日期时才使用默认最近30天。
- [x] 新增`SearchRelevancePolicy`：优先要求标题或摘要包含查询中的公司/主题核心词，对泛化财务词命中但缺少标的的结果进行过滤，并按标题、摘要命中强度排序。
- [x] `knowledge_search`按公告、结构化财报、相关新闻平衡编排`top_k`，避免宽泛新闻占满结果；输出各来源命中数和实际窗口。
- [x] 同行行业识别改为使用个股概要`f127`行业名称与板块归属精确匹配，不再把全行业列表交集作为主路径；行业归属、个股概要和成分股三个端点统一使用稳定的`push2delay`域名。
- [x] 真实药明康德历史搜索保留`2025-01-01..2025-12-31`，无关新闻命中数为0，返回公司公告与结构化财报；同行估值连续3次返回医疗服务行业及10只成分股。

#### 最终回答可读性门禁（2026-07-21）

- [x] 确认最终回答的JSON数组是Agent内部传输协议，服务端已经渲染为Markdown；不可读的直接原因是股票业务Prompt允许模型把调查过程、假设编号、工具轮次和过多并列判断写入用户答案。
- [x] 股票业务Action Prompt增加用户可读性约束：先直接回答问题，结论最多2条、关键依据最多4条、主要风险最多3条、后续动作最多2条，每条只表达一个观点。
- [x] 最终回答禁止出现`H1/H2`、`tool_call_round`、`selected_gap`、精确假设置信度等内部调查术语；业务字段需转换为普通中文，不得复述工具调用过程。
- [x] 决定方向的关键证据缺失时，首句必须明确“目前证据不足以判断”或“只能作暂定判断”，不得以宽泛同行估值等替代证据形成明确偏多/偏空结论。
- [x] Validation Prompt同步增加长度、内部术语、证据缺口和首句直答校验；`StockBusinessPromptAcceptanceTest`通过JDK 21直接编译与执行，未使用Maven。

#### 投资结论确定性语义门禁（2026-07-21）

- [x] 新增`InvestmentAssessmentPolicy`，由业务代码根据覆盖率、利润/收入增速背离、非经常性损益、现金转化、报告期ROE、历史估值和宽泛行业同行口径计算最终声明权限，不再让模型自由推导强结论。
- [x] `stock_factor_profile`新增`assessment_readiness`、`maximum_conclusion_strength`、`directional_conclusion_allowed`、`claim_permissions`、`required_gaps`、`prohibited_claims`、`required_answer_stance`和`required_opening`。
- [x] 当年度利润与收入增速明显背离且非经常性损益缺失时，方向性结论强度降为`TENTATIVE`，并禁止“盈利质量优异、利润主要来自主营、具备明确投资价值”等声明。
- [x] 单季度ROE不得授权“高ROE”；东方财富行业板块同行只作为宽泛行业背景，不得授权“明显低估”或用PB溢价推断高ROE。
- [x] 新增`NewsEvidenceSemantics`：新闻区分`PRIMARY_SUBJECT/MENTION_ONLY`，标记`INSTITUTION_OPINION`和`MARKET_REACTION`，并固定`supports_intrinsic_value=false`、`supports_risk_resolution=false`；公告元数据显式标记`document_content_read=false`。
- [x] 股票业务Prompt要求严格服从`claim_permissions`和`required_opening`，监管/诉讼/制裁事件先查官方公告；画像成功后不再重复调用宽泛同行比较，也不得用财务趋势工具伪装关闭盈利归因或年度ROE缺口。
- [x] 在StockMind自身`application.yml`启用框架已有的最终答案验证入口，使股票业务Validation Prompt实际执行；未修改框架代码或框架默认配置。
- [x] 新增投资声明权限、新闻证据语义固定样本，并扩展因子工具、知识搜索、业务Prompt和工具Schema回归；JDK 21直接编译和10项相关验收通过，未使用Maven。

#### 画像跟进流程与估值口径回归修复（2026-07-21）

- [x] 定位最新日志中的三条根因链：画像完整明细过长导致关键字段被模型视为截断；不可补齐缺口仍被当作可执行任务；2026年一季度EPS 1.59元被错误标成TTM EPS，生成77.38倍伪市盈率。
- [x] `stock_factor_profile`默认改为`COMPACT`，优先返回结论门禁、关键指标、分类摘要和`follow_up_policy`；只有明确审计明细时才使用`detail_level=FULL`。
- [x] `follow_up_policy`显式返回`allowed_tools`、`forbidden_tools`、`unresolvable_with_available_tools`和重复策略；默认`BALANCED`画像成功后不得更换风格重跑，也不得用财务趋势、指标计算或同行工具尝试关闭不可解决缺口。
- [x] `financial_metric_calculator`增加跨字段口径校验：季度累计EPS不能用于TTM或年度静态PE；`pe_basis=ttm`必须显式提供`eps_basis=ttm_eps`，错误统一返回`PE_BASIS_INCOMPATIBLE`。
- [x] 股票业务Prompt要求投资价值任务直接采用画像或行情已有TTM PE；公告正文未读取时不得从标题未命中推断“未发现/不存在”风险。
- [x] 最终动作协议补充业务硬约束：`final_answer`必须是本轮JSON最外层字段，与`selected_action`同级，禁止嵌套后导致渲染和最终校验失效。
- [x] JDK 21直接编译与11项相关验收通过，差异格式检查通过，全程未使用Maven；本项未编辑Agent框架文件，工作区既有的3个框架差异保持在任务范围外（当前差异哈希已单独记录，未据此回退用户改动）。

- [x] 阶段2退出标准：单股分析能够说明当前价格隐含的盈利与估值假设，并输出可追溯的情景区间；样本不足时只输出敏感性矩阵。

### 阶段3：全A股因子筛选

任务：

- `F3-01`：建立A股证券基础列表及交易状态；
- `F3-02`：批量加载point-in-time行情和财务快照；
- `F3-03`：实现基础过滤；
- `F3-04`：实现行业内分位和缩尾；
- `F3-05`：实现四种投资风格权重；
- `F3-06`：实现行业数量约束和Top N；
- `F3-07`：实现筛选结果风险复核；
- `F3-08`：暴露`a_share_factor_screen`工具。

退出标准：推荐类问题先产生可审计候选池，再做公告和新闻复核，不再由模型直接指定两只股票。

### 阶段4：行业专属因子

优先顺序：

1. 白酒；
2. CXO/创新药产业链；
3. 银行；
4. 半导体；
5. 公用事业和高股息。

行业因子以插件式业务计算器接入，不修改Agent框架：

```java
interface IndustryFactorCalculator {
    boolean supports(String industryCode);
    List<FactorValue> calculate(PointInTimeStockSnapshot snapshot);
}
```

## 11. 测试和验收

### 11.1 单元测试

每个计算器至少覆盖：

- 正常数据；
- 缺失数据；
- 分母为零；
- 负数PE或亏损；
- 不可比财务期间；
- 低样本同行；
- 未来数据被过滤；
- 边界阈值；
- BigDecimal精度与舍入。

所有公式测试使用固定输入，不访问外部网络。

### 11.2 Provider契约测试

验证：

- `as_of`不会返回未来数据；
- 标准化代码映射正确；
- 财务报表期间能够正确对齐；
- 数据源单位被标准化；
- 超时、空结果和依赖失败返回稳定业务错误；
- 当前实时价格和历史收盘价不会混用。

### 11.3 关键回归用例

#### 用例A：指数与股票代码

```text
输入：SZ000001
期望：平安银行

输入：SH000001
期望：上证指数

输入：000001，且业务对象类型未明确
期望：返回代码歧义，不静默猜测
```

#### 用例B：历史时点防穿越

```text
输入：SH600519，as_of=2026-07-17
期望：估值计算使用2026-07-17或之前最后交易日价格
禁止：使用2026-07-20实时价格1327.5元
```

#### 用例C：药明康德盈利质量

```text
已知：2025年收入同比约15.84%，净利润同比约102.65%
期望：触发PROFIT_GROWTH_DIVERGES_FROM_REVENUE
期望：要求检查非经常性损益，不直接输出主营增长102.65%
```

#### 用例D：五粮液盈利预测

```text
已知：2026/2027/2028预测EPS依次为7.32/6.30/5.07
期望：增长状态为DECELERATING或TURNING_NEGATIVE
禁止：仅因2026年前向PE较低就输出“明显低估”
```

#### 用例E：贵州茅台同行估值

```text
期望：清楚区分“包含标的的前10成分股中位数”和“排除标的后的纯同行中位数”
期望：标的TTM PE只与同行TTM PE比较
```

### 11.4 集成测试

至少覆盖：

- 贵州茅台单股因子画像；
- 药明康德单股因子画像；
- 五粮液单股因子画像；
- 历史`as_of`画像；
- 同行接口失败时的部分覆盖；
- 研报样本不足时不输出强预期评分；
- 业务工具JSON Schema可被Agent正常注册和调用。

### 11.5 当前验证方式

当前阶段使用JDK 21直接编译本批次涉及的纯业务代码，并运行无测试框架依赖的固定样本验收程序。每个阶段结束时同时执行`git diff --check`、框架差异哈希检查及现有可运行验收程序，确认没有破坏既有工具。

## 12. 可观测性与审计

每次因子画像记录：

- `request_id`；
- `symbol`和规范化代码；
- `requested_as_of`和`effective_trade_date`；
- 数据源及数据集ID；
- 因子策略版本；
- 每个因子的公式、原始值和质量；
- 同行/行业样本数；
- 覆盖率；
- 被过滤的未来数据数量；
- Provider失败和降级情况；
- 总耗时与各计算器耗时。

日志不得只记录综合评分，必须能够还原评分来源。

## 13. 性能与缓存

### 13.1 单股画像目标

- 无缓存P95：不高于8秒；
- 有缓存P95：不高于1秒；
- 单个Provider失败不得导致其他分类因子全部失败；
- 相同请求内行情、财报、研报和同行数据只加载一次。

### 13.2 缓存键

```text
normalized_symbol
+ as_of
+ dataset_type
+ interval
+ adjustment
+ provider
+ policy_version
```

历史数据可长期缓存；实时行情使用短TTL。缓存属于业务数据优化，不改变框架层的工具缓存和执行协议。

## 14. 风险与控制措施

| 风险 | 控制措施 |
|---|---|
| 数据源字段变化 | Provider契约测试和稳定业务错误码 |
| 不同来源口径不一致 | 快照中保留来源、期间和口径，不自动混算 |
| 历史数据穿越 | 所有Provider强制`published_date/as_of`过滤 |
| 因子重复计权 | 分类内先聚合，高相关指标不重复进入总分 |
| 小样本行业分位失真 | 少于8个有效样本不评分 |
| 模型覆盖计算结果 | Prompt明确模型只解释，不重算或修改分数 |
| 综合分制造虚假确定性 | 覆盖率门禁，始终输出分项和限制 |
| 推荐集中于单一行业 | 行业中性化及`max_per_industry`约束 |
| 框架层被业务需求侵入 | 每批检查`javascope-*`零改动 |

## 15. 第一轮实际执行清单

接下来按以下顺序开始开发，不并行扩大范围：

1. 完成`F0-01`至`F0-06`，建立point-in-time快照；
2. 完成因子领域模型和质量枚举；
3. 先实现估值、盈利质量、成长三个计算器；
4. 使用贵州茅台、药明康德、五粮液完成回归；
5. 再实现预期修正、动量风险和股东回报；
6. 聚合成`StockFactorProfileService`；
7. 只在最后增加`FactorAnalysisTools`薄适配和业务Prompt规则；
8. 阶段1全部验收后再开始情景估值；
9. 单股因子稳定后再启动全A股筛选，避免过早引入批量计算和缓存复杂度。

第一轮明确不做：

- 不修改Agent框架；
- 不调整ReAct流程；
- 不实现交易下单；
- 不输出确定性买卖建议；
- 不立即建设所有行业专属指标；
- 不在历史估值数据不足时伪造历史分位；
- 不在全市场数据能力完成前让模型直接推荐股票。

## 16. 阶段1完成定义（Definition of Done）

阶段1只有同时满足以下条件才算完成：

- [x] 框架模块零改动；
- [x] 股票和指数代码无歧义；
- [x] 历史`as_of`无未来价格、财报和研报泄漏；
- [x] 六类因子均由业务代码计算；
- [x] 每个因子包含公式、来源、时点、质量和限制；
- [x] 分类评分和综合评分具备覆盖率门禁；
- [x] 同行比较使用同一估值口径；
- [x] 机构预测能够识别上调、下调和低样本；
- [x] 财务增长异常能够生成确定性风险标记；
- [x] `stock_factor_profile`工具可以被Agent调用；
- [x] 贵州茅台、药明康德、五粮液回归用例通过；
- [x] 现有行情、财报、公告、新闻和投资价值工具回归通过；
- [x] JDK 21业务代码编译、固定样本验收和既有工具回归通过；
- [x] 工具执行结果能够还原因子计算过程。

完成该阶段后，股票分析的核心证据将从“模型读取零散查询结果”变为“业务系统输出可计算、可比较、可复核的因子画像”。

## 17. `a-stock-data-main` 外部接口复用方案

### 17.1 使用定位

当现有 StockMind Provider 无法提供某项因子所需原始数据时，允许从以下本地项目中查找可用接口：

```text
/Users/huang/Downloads/a-stock-data-main
```

该项目当前版本文档描述了43个公开端点、15个数据源，代码许可证为Apache License 2.0。可将其中的端点协议、字段映射、请求头、限流经验和降级策略作为StockMind业务数据适配的参考。

使用时必须遵守以下边界：

- 它是接口资料和实现参考，不作为运行时Python依赖引入StockMind；
- Java实现放在`stockmind-infrastructure`，业务接口放在`stockmind-application`；
- 因子公式仍放在`stockmind-application`，不能复制到Provider；
- 不修改任何`javascope-*`框架模块；
- 不因项目文档声称“可用”就直接进入生产，必须先做独立验收；
- Apache 2.0只覆盖该项目代码，第三方数据端点自身的使用条款、访问频率和数据授权需要单独遵守；
- 从该项目实质性改写代码时保留必要的来源和许可证说明；
- 公开、免Key不等于官方承诺稳定，也不等于可以无限频率调用。

### 17.2 已识别的可复用接口

#### A. 因子MVP直接相关

| 数据能力 | 参考函数/端点 | 数据源 | 用途 | 接入优先级 | 结论 |
|---|---|---|---|---:|---|
| 股票行业、股本、上市日期 | `eastmoney_stock_info` | 东方财富push2 | 证券主数据、上市天数、流通比例、行业归属 | P0 | 建议接入 |
| 一致预期EPS交叉验证 | `ths_eps_forecast` | 同花顺F10 | 与东财研报聚合结果交叉验证机构数和年度EPS | P1 | 当前时点备用源 |
| 季报37字段快照 | `client.finance` | 通达信/mootdx | ROE、BVPS、股本、EPS等当前财务快照 | P1 | 验证后作为补充源 |
| 新浪财务三表 | `sina_financial_report` | 新浪 | 现有财务Provider字段映射校验和备用实现 | 已接入 | 保持现状 |
| 腾讯PE/PB/市值 | `tencent_quote` | 腾讯 | 现有行情Provider字段索引校验 | 已接入 | 保持主源 |
| 同花顺历史K线 | `d.10jqka.com.cn/v6/line/...` | 同花顺 | 腾讯历史K线不可用时的独立备胎 | P1 | 只作降级源 |
| 官方实时行情 | 上交所/深交所官方行情端点 | 交易所 | 腾讯或通达信异常时交叉验证 | P1 | 只作降级/核验 |

说明：

- `ths_eps_forecast`返回的是当前网页一致预期，不能天然满足历史`as_of`，不得用于历史回测；
- mootdx财务快照适合当前核验，但需要确认报告期、披露时点和字段口径后才能进入point-in-time快照；
- mootdx K线为不复权原始价格，本项目继续优先使用腾讯前复权日K，避免跨除权日收益失真；
- 该工具库中的腾讯代码对指数仍使用代码前缀推断，StockMind必须坚持`SH000001`与`SZ000001`显式资产标识，不能原样照搬其模糊输入逻辑。

#### B. 风险和筹码因子

| 数据能力 | 参考函数/报表 | 核心字段 | 可形成的业务因子/风险标记 | 优先级 |
|---|---|---|---|---:|
| 限售解禁 | `lockup_expiry` / `RPT_LIFT_STAGE` | 解禁日期、实际可流通股数、占总股本比例 | 未来30/90日解禁比例、`UPCOMING_LOCKUP_RELEASE` | P0 |
| 融资融券 | `margin_trading` / `RPTA_WEB_RZRQ_GGMX` | 融资余额、买入、偿还、融券余额 | 20日融资余额变化、融资/流通市值、杠杆拥挤度 | P1 |
| 股东户数 | `holder_num_change` / `RPT_HOLDERNUMLATEST` | 股东数、环比、户均持股 | 筹码集中趋势、持有人扩散风险 | P1 |
| 大宗交易 | `block_trade` / `RPT_DATA_BLOCKTRADE` | 成交额、折溢价、买卖营业部 | 近20/60日大宗净规模、平均折价率 | P2 |
| 日级资金流 | `stock_fund_flow_120d` | 主力、大单、超大单净流入 | 5/20/60日资金流强弱，仅作交易状态 | P2 |
| 资金流备用源 | `fund_flow_backup` | 新浪日度净额、成交额 | 东财push2失败时降级 | P2 |
| 龙虎榜 | `dragon_tiger_board` / `dragon_tiger_backup` | 上榜原因、净买额、席位 | 异常交易事件和短期拥挤风险 | P2 |
| 板块归属 | `eastmoney_concept_blocks` | 行业、概念、地域、领涨股 | 行业映射、概念暴露，不直接计基本面分 | P1 |
| 行业排名 | `industry_comparison` | 行业涨跌、上涨/下跌家数 | 行业相对动量和市场宽度 | P1 |

风险和筹码数据默认不直接改变估值、质量和成长分数。它们进入：

- `momentum_risk`分类；
- `warnings`；
- 推荐候选的二次风险复核；
- 情景估值中的风险溢价解释。

其中“主力资金流”“筹码集中”“龙虎榜席位”等字段只能作为市场交易状态，不能被模型写成机构真实持仓或公司内在价值变化。

#### C. 事件与数据源降级

| 数据能力 | 主源 | 参考备用源 | 业务用途 | 优先级 |
|---|---|---|---|---:|
| 公司公告 | 巨潮 | 深交所官方公告、东财沪市公告 | 公司事件事实核验 | P0 |
| 财务三表 | 新浪 | 同花顺F10财务JSON | 主源失败时保持财务画像部分可用 | P1 |
| 个股新闻 | 东方财富 | 新浪7×24关联股票 | 新闻主源失败时补充 | P2 |
| 全市场快讯 | 东方财富 | 财联社v1签名接口 | 政策、行业和宏观事件 | P2 |
| 龙虎榜 | 东方财富 | 上交所、深交所官方 | 权威一手降级 | P2 |
| 北向资金 | 同花顺 | HKEX官方日统计 | 市场层风险背景 | P2 |

公告降级应优先接入，因为公司公告是投资价值分析中可靠性最高的事件来源之一。新闻、快讯和热榜只作为解释性证据，不进入核心基本面评分。

### 17.3 暂不纳入第一阶段的接口

以下接口虽然可用，但与长期单股因子画像关系较弱，第一阶段不接入：

- 涨停池、炸板池、跌停池、连板梯队；
- 同花顺涨停揭秘；
- ETF期权T型报价、希腊字母和隐含波动率；
- 热榜、人气榜、概念热度；
- 互动易问答；
- iwencai自然语言搜索；
- 分钟级北向资金；
- 五档盘口和逐笔成交。

这些能力可在后续“短线交易、事件驱动、期权和舆情”专项中接入，不应干扰当前的价值、质量和成长因子建设。

### 17.4 该项目仍不能补齐的数据

读取当前版本后，以下关键能力仍需要StockMind自行建设或另找可靠数据源：

1. **历史PE/PB序列**：腾讯只提供当前估值；需要基于历史价格与point-in-time TTM财务重建，或者接入可靠历史估值源。
2. **扣非净利润和非经常性损益结构化历史**：新浪三表不保证直接提供完整扣非口径，需要公告/财报结构化字段或其他F10源。
3. **完整资本开支与自由现金流口径**：需要验证购建固定资产等现金流字段后由业务代码计算。
4. **历史一致预期快照**：同花顺网页主要提供当前共识；30/90/180日预测修正需要按研报发布日期重建或自行保存每日快照。
5. **全A股证券主数据**：项目有个股信息和巨潮orgId映射，但没有完整、可回溯的ST、停牌、上市状态和退市状态主数据服务。
6. **行业专属经营指标**：白酒批价、渠道库存，CXO在手订单、客户集中度等仍需专项来源。
7. **历史股本变化的point-in-time序列**：计算历史市值、每股指标和回购稀释时需要独立校验。

因此，`a-stock-data-main`是重要的数据接口目录和备用源参考，但不能替代StockMind的因子引擎、证券主数据和历史快照体系。

### 17.5 建议新增的业务Provider

根据可复用端点，在`stockmind-application`增加以下业务接口；实现放在`stockmind-infrastructure`：

```text
StockProfileProvider
    └── 行业、股本、流通股本、上市日期、证券状态

ConsensusSnapshotProvider
    └── 指定as_of的一致预期及样本质量

LockupReleaseProvider
    └── 历史和未来限售解禁

MarginTradingProvider
    └── 日级融资融券

ShareholderStructureProvider
    └── 股东户数和户均持股

BlockTradeProvider
    └── 大宗交易

FundFlowProvider
    └── 日级资金流及来源口径

MarketBreadthProvider
    └── 行业排名、上涨下跌家数
```

Provider返回原始、标准化业务数据，不返回“低估”“主力吸筹”“建议买入”等观点。

### 17.6 东方财富统一访问控制

该接口库明确提示东方财富的`datacenter/push2/reportapi/search/np-weblist`共享IP级风控。StockMind若继续接入东财端点，应在`stockmind-infrastructure`建立统一业务访问门：

```text
EastmoneyRequestGate
    ├── 复用HTTP连接
    ├── 按host串行或有限并发
    ├── 最小请求间隔
    ├── 429/5xx退避
    ├── 403快速熔断
    ├── 请求级缓存
    └── 独立数据源降级
```

约束：

- 批量全市场筛选不得逐股票同步调用多个东财端点；
- 优先寻找能够一次返回全市场或整行业的批量端点；
- 个股独有数据只在Top N深度复核阶段调用；
- 403不连续重试，直接标记数据源受限并切换独立备用源；
- 限流和熔断属于股票基础设施实现，不进入Agent框架重试逻辑；
- 东财接口失败只降低对应因子覆盖率，不得导致整个因子画像失败。

### 17.7 外部端点准入流程

任何从`a-stock-data-main`选中的接口都必须完成以下步骤：

1. **登记**：记录数据源、URL、用途、字段、鉴权、频率限制和参考章节；
2. **存活测试**：使用至少3只不同市场股票验证HTTP状态和非空结果；
3. **字段校验**：逐字段核对单位、比例、日期和空值；
4. **时点校验**：验证是否支持`as_of`，不支持时明确标为`CURRENT_ONLY`；
5. **交叉验证**：至少选一个已有来源或公司公告核对关键数值；
6. **错误建模**：定义超时、限流、空数据、字段变化和不可用错误码；
7. **降级设计**：明确独立备用源，避免主备共享同一风控域；
8. **契约测试**：录制或固定脱敏样本，避免单元测试依赖网络；
9. **上线观察**：记录成功率、P95耗时、空结果率和字段解析失败率；
10. **进入因子**：只有完成以上步骤的数据才能参与评分，否则只能作为补充信息。

建议登记模型：

```json
{
  "provider": "eastmoney",
  "dataset": "lockup_release",
  "endpoint_class": "PUBLIC_UNAUTHENTICATED",
  "point_in_time": true,
  "current_only": false,
  "unit_contract": "shares=shares, ratio=decimal",
  "rate_limit_policy": "eastmoney-request-gate-v1",
  "fallback_provider": null,
  "factor_usage": "risk_warning",
  "admission_status": "CANDIDATE"
}
```

### 17.8 对实施阶段的具体影响

阶段0新增：

- 建立`ExternalDatasetRegistry`或等价配置；
- 为每个Provider标记`POINT_IN_TIME`、`CURRENT_ONLY`或`UNSUPPORTED`；
- 建立东方财富业务访问门；
- 将上交所/深交所指数与股票显式区分，不能复用参考项目的模糊前缀推断。

阶段1优先复用：

- 腾讯行情；
- 新浪三表；
- 东财研报、分红和行业成分；
- 东财股票基础信息；
- 同花顺一致预期作为当前时点交叉验证；
- 官方公告备用源。

阶段2再接入：

- 解禁；
- 两融；
- 股东户数；
- 大宗交易；
- 资金流及新浪备用源；
- 行业市场宽度。

阶段3全市场筛选必须优先解决证券主数据和批量数据端点，不能简单循环调用`a-stock-data-main`中的个股函数，否则会产生严重性能和风控问题。
