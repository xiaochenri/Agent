# 股票业务工具与规则分层

本文是股票业务模块的单一职责说明。新增工具或规则时，应先确定所属层级，避免在工具描述、Prompt、业务代码和校验器中重复实现同一限制。

## 分层原则

| 层级 | 负责内容 | 不负责内容 |
| --- | --- | --- |
| 工具 Schema 与工具实现 | 参数合法性、确定性计算、数据来源、输出口径 | 决定完整调查顺序、规定回答措辞 |
| 工具描述 | 说明何时使用、返回什么、最重要的语义边界 | 复制整套业务系统提示词 |
| Action/Plan Prompt | 选择最小充分工具、区分证据类型、控制结论强度 | 建立不可变工具白名单、重复校验 JSON Schema |
| 业务策略代码 | 计算覆盖率、结论强度、证据缺口和具体强声明权限 | 因单个缺口否定所有其他证据、强制固定开场白 |
| 语义校验器 | 拒绝可客观证明的口径或公式错误 | 因表达风格、条目数量或建议工具未被采用而判失败 |
| 最终答案校验 | 事实可追溯、没有越过明确证据权限 | 用格式问题阻断已经合法完成的工具动作 |

硬限制只用于错误结果无法靠自然语言风险提示修复的情况，例如证券代码缺失、EPS口径不兼容、公式不一致、把公告标题当作正文事实。其余内容默认是建议或结论强度限制。

## 工具职责

### 综合入口

| 工具 | 使用场景 | 边界 |
| --- | --- | --- |
| `stock_snapshot_analysis` | “最近怎么样”等行情、技术、事件和基本面的宽泛概览 | 不计算六因子投资价值评分 |
| `stock_factor_profile` | 投资价值、估值质量、基本面综合判断 | 给出量化画像、声明权限和证据缺口；后续工具策略为建议 |
| `scenario_valuation_analysis` | 合理价、目标区间、估值敏感性 | 使用Forward EPS与Forward PE；假设不完整时不给唯一目标价 |

### 行情与技术面

| 工具 | 使用场景 |
| --- | --- |
| `market_quote` | 当前/指定日期行情与TTM PE、PB |
| `price_move_analysis` | 区间收益、回撤、反弹和量价状态 |
| `historical_bars` | 原始日线、K线或图表数据 |
| `technical_indicator_snapshot` | 未指定单项指标的技术面快照 |
| `moving_average_analysis`、`macd_analysis`、`rsi_analysis`、`bollinger_analysis`、`atr_analysis`、`volume_analysis` | 用户明确要求单项技术指标 |
| `benchmark_performance` | 个股相对市场基准表现 |
| `sector_performance` | 所属行业当日表现 |

技术指标、股价和市场反应只解释价格状态，不证明内在价值、事件原因或风险解除。

### 财报、估值与机构观点

| 工具 | 使用场景 | 边界 |
| --- | --- | --- |
| `latest_financial_report` | 最新已披露财务事实 | 不替代多期趋势或综合因子画像 |
| `financial_report_metrics` | 指定报告期的净利润、股本、基本EPS取数 | 只负责结构化取数 |
| `financial_metric_calculator` | 指定报告期EPS/PE确定性计算 | 季度累计EPS不能冒充年度或TTM EPS |
| `financial_trend_analysis` | 多期增长、利润率、现金流和负债趋势 | 不重复计算综合评分 |
| `analyst_consensus_forecast` | 年度EPS一致预期与Forward PE | 全部结果属于机构观点 |
| `research_report_search` | 评级、预测和研报原文 | 观点不能升级为事实 |
| `peer_valuation_comparison` | 宽泛行业估值参照 | 不等同于严格商业可比公司组 |
| `dividend_analysis` | 已实施分红与滚动股息率 | 不代表未来分红承诺 |

### 事件与资料发现

| 工具 | 使用场景 | 边界 |
| --- | --- | --- |
| `company_announcements` | 公司官方公告元数据 | 未读取正文时不推断动机、影响或事件不存在 |
| `news_search` | 新闻、市场背景和机构观点 | 遵守每条 `evidence_semantics` |
| `knowledge_search` | 来源尚不明确的跨新闻/公告/财报资料发现 | 已知目标来源时使用专用工具 |

## 投资价值结论规则

1. 覆盖率低于 60%：不输出综合方向。
2. 覆盖率为 60%-80%：只作暂定、条件性判断。
3. 覆盖率达到 80%：可以解读综合分；具体证据缺口可降低 `maximum_conclusion_strength`，但不会自动否定所有方向判断。
4. `claim_permissions` 按声明逐项生效。例如 `high_roe=false` 只禁止“高ROE”这一强声明。
5. `evidence_gaps` 是待验证条件；模型可补充会实质改变结论的证据，也可带着缺口形成条件性回答。
6. `follow_up_policy.mode=ADVISORY`，`suggested_tools` 不是白名单。禁止因为选择了另一个相关工具而判定业务失败。

## 最终答案的论据映射

调用过业务工具的任务必须为每条 `core_conclusions` 提供一条对应的
`conclusion_evidence`：

```json
{
  "conclusion": "与core_conclusions中的文本完全一致",
  "evidence": [
    {
      "fact": "直接支持结论的可展示数据或事实",
      "source_step": "tool_call_round_N",
      "source_type": "财报、行情、公告、机构预测或因子画像",
      "as_of": "数据日期或报告期",
      "basis": "TTM、Forward、同报告期同比或公告元数据等口径"
    }
  ],
  "limitations": ["这条结论尚未解决的证据缺口"]
}
```

`source_step` 只用于内部校验，不显示给用户。用户响应显示结论、事实、来源类型、日期、
口径和限制。语义校验先确定性检查映射和股票证据权限，通过后再进入独立模型验证；仅需
改写答案的校验失败不会触发新的工具调用。

## 变更检查清单

- 新规则是否能由现有 Schema、计算代码或证据语义直接保证？如果能，不要再复制到三份 Prompt。
- 新限制是在防止事实错误，还是只是在偏好某种调查路径或写作格式？后者应做软指导。
- 某个证据缺口是否同时禁止了所有能补齐它的工具？若是，说明形成了自锁。
- 工具描述是否与综合入口争抢同一场景？聚焦工具应明确自己的窄职责。
- 校验失败是否会丢弃本轮合法工具动作？非关键推理字段不应阻断动作执行。
