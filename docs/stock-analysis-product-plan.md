# StockMind 股票分析业务扩展与 Stream Chat 重设计报告

> 日期：2026-07-11  
> 范围：业务能力规划、Java 实现方式、主要对话页信息架构与交付路线；本报告阶段不修改业务代码。

## 1. 现状结论

当前项目的 Agent 运行时、工具注册、规划、校验、会话记忆和知识库检索骨架已经具备，适合继续扩展股票业务，但业务数据与分析层还处于 Demo 阶段：

- `market_quote` 使用基于日期和代码生成的 mock 行情，不能支持真实分析。
- `stock_snapshot_analysis` 自行调用真实行情、技术、新闻、公告、行业和财报接口，不接受调用方拼装的证据快照。
- `news_search` 是失败占位实现。
- `knowledge_search` 可检索 OceanBase 向量知识库，是现阶段最接近可用的业务能力。
- 股票工具已按行情、技术指标、研究证据和综合分析拆分到 bootstrap 层的独立组件；领域模型、应用服务和基础设施适配器仍是后续持续演进边界。
- `stream-chat.html` 是 920px 宽的三段式 Demo。`.app { max-width: 920px; margin: 0 auto; }` 是页面无法铺满的直接原因；页面也没有自选股、行情上下文、图表、分析卡、任务过程和会话管理入口。

建议把产品定位从“股票聊天 Demo”升级为“对话驱动的股票研究工作台”：聊天负责理解意图与组织解释，确定性的行情、指标、筛选和回测由 Java 工具负责，所有结论能追溯到数据时间、参数和证据。

## 2. 股票分析知识与首批工具

技术分析使用历史价格与成交量寻找可能的趋势或交易区间，但应与基本面、事件信息和风险分析结合。指标输出必须称为“信号/证据”，不能称为“预测”或直接生成确定性买卖建议。[Fidelity 技术分析概览](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/what-is-technical-analysis)

### 2.1 P0：首期必须实现

| 工具 | 输入 | 核心计算/默认参数 | 输出与解释 |
|---|---|---|---|
| `historical_bars` | symbol、market、interval、start/end、adjustment | 获取 OHLCV；A 股必须明确前复权/后复权/不复权 | bars、source、timezone、adjustment、asOf、缺失区间 |
| `moving_average_analysis` | bars、periods | SMA/EMA，默认 5/10/20/60；金叉死叉、价格与均线位置、均线斜率 | 序列、最新值、crossovers、trendState、warmup |
| `macd_analysis` | bars、fast/slow/signal | 默认 EMA 12、26，MACD 信号线 9；DIF=EMA12-EMA26，DEA=EMA9(DIF)，histogram=DIF-DEA | DIF/DEA/柱值、最近交叉、零轴位置、动量状态、背离候选。MACD 在震荡区易反复交叉，解释必须提示该局限。[MACD 说明与计算](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/macd) |
| `rsi_analysis` | bars、period | Wilder RSI，默认 14；`100 - 100/(1+平均涨幅/平均跌幅)` | RSI 序列、70/30 区域、穿越、背离候选；强趋势中超买/超卖可持续，不能单独反转定性。[RSI 说明](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/RSI) |
| `bollinger_analysis` | bars、period、stdDev | 默认 20 日 SMA、上下 2 倍标准差 | upper/middle/lower、bandwidth、%B、收口/扩张、触带；必须与其他指标确认，触及上轨不等于卖出。[布林带说明与计算](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/bollinger-bands) |
| `atr_analysis` | bars、period | 默认 14；TR 取 high-low、abs(high-prevClose)、abs(low-prevClose) 最大值，Wilder 平滑 | ATR、ATR%、波动分位、异常波动；ATR 不表示方向。[ATR 说明与计算](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/atr) |
| `volume_analysis` | bars、period | 成交量均线、量比、价量配合、OBV | volumeRatio、OBV 趋势、放量/缩量、价量背离候选。[OBV 说明](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/obv) |
| `technical_summary` | 上述结构化结果 | 规则聚合，不重新计算指标 | 趋势/动量/波动/量能四维评分、支持证据、冲突证据、风险、数据局限；禁止只给单一总分 |

首期推荐的默认组合：

- 趋势：EMA20/EMA60 + MACD。
- 动量：RSI14。
- 波动：Bollinger(20,2) + ATR14/ATR%。
- 量价：成交量均线/量比 + OBV。
- 输出策略：每个信号包含 `direction`、`strength`、`observedAt`、`evidence`、`limitations`；综合结论同时列出支持与冲突信号。

### 2.2 编码前统一约定

以下约定是所有指标实现的前置条件，否则不同工具会对同一股票产生不可比较的结果。

#### 2.2.1 输入 Bar 模型

```java
public record MarketBar(
        String instrumentId,      // 600519.SH，不接收含糊代码
        BarInterval interval,     // DAY_1、MINUTE_5 等
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal amount,
        AdjustmentMode adjustment // NONE、FORWARD、BACKWARD
) {}
```

校验顺序：

1. 按 `openTime` 升序排序；相同时间只能保留一条，数据冲突时报错而非静默覆盖。
2. `open/high/low/close > 0`，`volume/amount >= 0`。
3. `high >= max(open, close, low)` 且 `low <= min(open, close, high)`。
4. 全部 Bar 的 instrument、interval、adjustment 必须一致。
5. 停牌日不人工补零价 Bar；交易日缺口记录进 `dataQuality.missingBars`。
6. 指标只使用已收盘 Bar；实时未收盘 Bar 必须标记 `partial=true`，默认不参与正式信号。

为了让所有指标对初始化方式达成一致，首期统一采用：

- SMA：窗口内算术平均。
- 标准差：总体标准差，即除以 `n`；必须在代码和响应中固定 `population`，不能有的工具除以 `n`、有的除以 `n-1`。
- EMA：首个 EMA 使用前 `period` 个 close 的 SMA 作为 seed，之后 `EMA_t = α × close_t + (1-α) × EMA_(t-1)`，`α=2/(period+1)`。
- Wilder 平滑：首值用前 `period` 项的算术平均，后续 `value_t=(previous×(period-1)+current)/period`。
- 内部计算建议 `double` 与 ta4j 对齐，API 输出统一舍入但数据库保留原始精度；禁止用已舍入值继续计算后续指标。

#### 2.2.2 通用请求与结果模型

```java
public record TechnicalAnalysisRequest(
        String instrumentId,
        BarInterval interval,
        LocalDate startDate,
        LocalDate endDate,
        AdjustmentMode adjustment,
        boolean includeSeries,
        Map<String, Object> parameters
) {}

public enum SignalDirection { BULLISH, BEARISH, NEUTRAL }
public enum SignalStrength { WEAK, MODERATE, STRONG }

public record TechnicalSignal(
        String code,              // MACD_BULLISH_CROSS
        SignalDirection direction,
        SignalStrength strength,
        Instant observedAt,
        String explanation,
        Map<String, BigDecimal> evidence,
        List<String> limitations
) {}

public record IndicatorPoint(
        Instant time,
        Map<String, BigDecimal> values,
        boolean valid             // warm-up 阶段为 false
) {}

public record IndicatorResult(
        String indicator,
        Map<String, Object> parameters,
        Map<String, BigDecimal> latest,
        List<IndicatorPoint> series,
        List<TechnicalSignal> signals,
        int warmupBars,
        DataQuality dataQuality
) {}
```

统一错误码：`INVALID_SYMBOL`、`INVALID_RANGE`、`INVALID_PARAMETER`、`INSUFFICIENT_BARS`、`INCONSISTENT_BARS`、`DATA_SOURCE_UNAVAILABLE`、`PARTIAL_BAR_EXCLUDED`。参数错误不可重试，供应商暂时故障可重试。

#### 2.2.3 数据量规则

接口不能只判断 `bars.size() >= period`。EMA、MACD 和 RSI 存在收敛期，建议：

```text
SMA/BOLL 最低：period
RSI 最低：period + 1；建议拉取 period + 100
ATR 最低：period + 1；建议拉取 period + 100
MACD 最低：slow + signal；建议拉取 slow + signal + 100
EMA60 建议：至少 60 + 150 根
```

`minimumBars` 是“能计算”，`recommendedBars` 是“适合解释”。处于两者之间时允许返回结果，但必须加入 `CONVERGENCE_RISK` warning。

### 2.3 历史行情工具详细规格

#### 2.3.1 `historical_bars`

职责仅限获取、规范化和缓存 OHLCV，不计算指标。

Agent 工具输入：

```json
{
  "instrument_id": "600519.SH",
  "interval": "1d",
  "start_date": "2025-01-01",
  "end_date": "2026-07-10",
  "adjustment": "forward"
}
```

执行算法：

1. 解析证券并查询交易所时区、交易日历。
2. 将自然日期转换成供应商查询范围，不使用服务器默认时区。
3. 请求 `MarketDataProvider.loadBars()`；供应商 DTO 在 infrastructure 层转换成 `MarketBar`。
4. 执行 Bar 校验、排序、去重和缺失交易日检测。
5. 计算数据集内容哈希并写缓存，生成不可变 `datasetId`。
6. 返回概要和可选的绘图序列；指标工具接收 `datasetId`，保证同一轮分析使用相同数据快照。

```java
public interface MarketDataProvider {
    BarDataset loadBars(HistoricalBarsQuery query);
}

public record BarDataset(
        String datasetId,
        List<MarketBar> bars,
        String source,
        Instant asOf,
        DataQuality dataQuality
) {}
```

验收用例：乱序数据可规范化；重复且相同的 Bar 可去重；重复但价格不同必须失败；跨春节不把非交易日算缺失；复权模式不同必须生成不同 datasetId。

### 2.4 均线分析详细规格

#### 2.4.1 计算

对窗口 `p`：

```text
SMA_t(p) = sum(close[t-p+1 ... t]) / p
EMA seed 位于 t=p-1：SMA_(p-1)(p)
EMA_t(p) = close_t × 2/(p+1) + EMA_(t-1) × (1-2/(p+1))
slopePct = (MA_t - MA_(t-lookback)) / abs(MA_(t-lookback)) × 100
```

首期默认 `periods=[5,10,20,60]`、`type=EMA`、`slopeLookback=5`。`period` 限制 2–250，必须升序去重。

#### 2.4.2 信号规则

- 金叉：上一有效点 `fast <= slow` 且当前点 `fast > slow`。
- 死叉：上一有效点 `fast >= slow` 且当前点 `fast < slow`。
- 多头排列：`close > MA5 > MA10 > MA20 > MA60`；空头排列反向。
- 趋势上行：选定主均线（默认 EMA20）的 `slopePct > +0.5%`；下行 `< -0.5%`；阈值配置化。
- 为避免把微小抖动当交叉，可配置 `minSeparationPct`，交叉后 `abs(fast-slow)/slow` 未达到阈值则标记 WEAK。

不要把“金叉”直接翻译为“买入”。输出应是“短周期均线向上穿越长周期均线，构成偏多趋势证据”。

### 2.5 MACD 详细规格

#### 2.5.1 公式和序列

```text
DIF_t = EMA(close, fast)_t - EMA(close, slow)_t
DEA_t = EMA(DIF, signal)_t
histogram_t = DIF_t - DEA_t
```

默认 `fast=12, slow=26, signal=9`；校验 `2 <= fast < slow <= 250`、`2 <= signal <= 100`。国内部分软件将柱值显示为 `2 × (DIF-DEA)`，本项目固定使用未乘 2 版本，并在响应 `histogramScale=1` 明示，防止与外部图表对不上。

MACD 的 DIF 只有在 slow EMA 有效后才有效；DEA 用首批 signal 个有效 DIF 的 SMA 初始化。建议 `warmupBars=slow+signal-2`，更早的点 `valid=false`。

#### 2.5.2 信号判定

按最新两个有效点判断：

```java
boolean bullishCross = prev.dif() <= prev.dea() && curr.dif() > curr.dea();
boolean bearishCross = prev.dif() >= prev.dea() && curr.dif() < curr.dea();
boolean aboveZero = curr.dif() > 0 && curr.dea() > 0;
boolean momentumExpanding = Math.abs(curr.histogram()) > Math.abs(prev.histogram());
```

强度建议：

- WEAK：发生交叉，但 DIF 与 DEA 距离小于收盘价的 `0.05%`。
- MODERATE：交叉距离达阈值，或柱值连续 2 根同方向扩大。
- STRONG：交叉方向同时得到零轴位置、EMA20/60 趋势和成交量确认；不能仅因远离零轴就定为 STRONG。

背离不在 MVP 中用两点随意判断。若实现，必须先用可配置 pivot 算法找到两个已确认价格高/低点，再比较对应 DIF；最后一个 pivot 需等待右侧 `rightBars`，所以属于滞后信号，并返回 pivot 时间和价格证据。

示例最新值：

```json
{
  "dif": 1.2384,
  "dea": 0.9821,
  "histogram": 0.2563,
  "histogram_scale": 1,
  "state": "ABOVE_SIGNAL_ABOVE_ZERO"
}
```

### 2.6 RSI 详细规格

#### 2.6.1 Wilder RSI 算法

```text
change_t = close_t - close_(t-1)
gain_t = max(change_t, 0)
loss_t = max(-change_t, 0)

初始 avgGain = first period gains 的平均
初始 avgLoss = first period losses 的平均
后续 avgGain_t = (prevAvgGain × (period-1) + gain_t) / period
后续 avgLoss_t = (prevAvgLoss × (period-1) + loss_t) / period
RS = avgGain / avgLoss
RSI = 100 - 100/(1+RS)
```

边界规则必须显式编码：

- `avgLoss=0 && avgGain>0`：RSI=100。
- `avgGain=0 && avgLoss>0`：RSI=0。
- 两者都为 0：RSI=50，表示无方向，而不是 NaN。

默认 `period=14`、`overbought=70`、`oversold=30`；阈值满足 `0 < oversold < 50 < overbought < 100`。

#### 2.6.2 信号

- 进入超买：previous RSI `<=70` 且 current `>70`；只表示动量强，不直接看空。
- 离开超买：previous `>=70` 且 current `<70`，可作为动量降温证据。
- 进入/离开超卖同理。
- 中轴：上穿 50 记偏多动量，下穿 50 记偏空动量。
- RSI 背离沿用 MACD 的确认 pivot 机制，首期可不开放。

### 2.7 Bollinger Bands 详细规格

#### 2.7.1 公式

```text
middle_t = SMA(close, period)
std_t = sqrt(sum((close_i-middle_t)^2) / period)
upper_t = middle_t + deviations × std_t
lower_t = middle_t - deviations × std_t
bandwidth_t = (upper_t-lower_t) / middle_t × 100
percentB_t = (close_t-lower_t) / (upper_t-lower_t)
```

默认 `period=20`、`deviations=2.0`。若 `upper==lower`，`percentB=0.5`、`bandwidth=0`，不得除零。

#### 2.7.2 信号

- 上/下轨突破：上一根在轨内，本根 close 穿出轨道。
- 回归轨内：上一根在轨外，本根回到轨内。
- 收口：当前 bandwidth 低于最近 `lookback=120` 个有效 bandwidth 的 20% 分位。
- 扩张：当前 bandwidth 高于 80% 分位且较上一根增加。
- 沿轨运行：最近 5 根至少 4 根 `percentB >= 0.8`（偏多）或 `<=0.2`（偏空）。

分位数样本不足 40 根时不输出收口/扩张信号。突破上下轨只表示相对位置和波动，不直接生成反转结论。

### 2.8 ATR 详细规格

#### 2.8.1 公式

```text
TR_t = max(
  high_t-low_t,
  abs(high_t-close_(t-1)),
  abs(low_t-close_(t-1))
)
初始 ATR = first period TR 的平均
ATR_t = (previousATR × (period-1) + TR_t) / period
ATRP_t = ATR_t / close_t × 100
```

第一根没有 previous close，其 TR 取 `high-low`。默认 period=14。跨复权除权点必须使用同一复权序列，否则 gap 会制造虚假 ATR。

#### 2.8.2 状态

- `VOLATILITY_EXPANDING`：ATR% 高于 120 根历史的 80% 分位，且连续 3 根总体上升。
- `VOLATILITY_CONTRACTING`：低于 20% 分位，且连续 3 根总体下降。
- `NORMAL`：其余情况。

ATR 不含方向。止损距离可以返回研究参数 `referenceDistance = multiplier × ATR`，但不得表述为适用于用户的个性化止损建议。

### 2.9 成交量与 OBV 详细规格

#### 2.9.1 计算

```text
volumeMA_t = SMA(volume, period)
volumeRatio_t = volume_t / volumeMA_t

OBV_0 = 0
close_t > close_(t-1): OBV_t = OBV_(t-1) + volume_t
close_t < close_(t-1): OBV_t = OBV_(t-1) - volume_t
close_t = close_(t-1): OBV_t = OBV_(t-1)
```

默认 `period=20`。若 `volumeMA=0`，`volumeRatio` 返回 null 并添加 `ZERO_VOLUME_WINDOW`，不能返回 Infinity。

#### 2.9.2 信号

- 放量：`volumeRatio >= 1.5`；显著放量：`>=2.0`。
- 缩量：`volumeRatio <=0.7`。
- 价量确认：日收益方向与 OBV 5 日斜率方向相同，并且 `volumeRatio>=1.2`。
- 价量背离：价格 pivot 与 OBV pivot 方向冲突；和其他背离一样必须用已确认 pivot，不能比较任意首尾两点。

A 股不同供应商的 volume 可能是“股”或“手”。provider 必须统一成股，原始单位写入元数据。

### 2.10 综合技术报告详细规格

`technical_summary` 不重新计算原始指标，只读取同一 datasetId 下的分析结果，避免参数和数据快照不一致。

#### 2.10.1 四维状态，而非神秘总分

```java
public record TechnicalDimension(
        String name,              // TREND/MOMENTUM/VOLATILITY/VOLUME
        SignalDirection direction,
        int confidence,           // 0..100，代表证据一致性，不是上涨概率
        List<String> supportingSignalIds,
        List<String> conflictingSignalIds
) {}
```

建议规则：

- 趋势：EMA20/60 位置与斜率、MACD 交叉和零轴。
- 动量：RSI 区域、中轴穿越、MACD 柱变化。
- 波动：ATR% 分位、BOLL bandwidth 状态；方向通常 NEUTRAL。
- 量能：量比、OBV 方向、价格与 OBV 是否一致。

置信度是确定性规则的“一致性分”，示例：某维度可用信号权重总和为 100，偏多权重 70、偏空 20、中性 10，则方向 BULLISH，`confidence=abs(70-20)=50`。响应必须说明它不是收益概率。

综合结论模板：

1. 数据范围与时间。
2. 核心状态：趋势、动量、波动、量能。
3. 支持证据，精确到指标值和发生时间。
4. 冲突证据。
5. 数据质量与方法局限。
6. 建议继续验证的基本面、行业或事件信息。

如果少于 3 个维度有效，不生成综合方向，只返回 `INSUFFICIENT_DIMENSIONS`。若趋势和动量方向相反，必须明确标记 `CONFLICTING_SIGNALS`，不能用平均分掩盖冲突。

### 2.11 Agent 工具方法骨架

```java
@AgentTool(
    name = "macd_analysis",
    title = "MACD 技术分析",
    description = "基于已缓存的历史收盘价计算 MACD；输出概率性技术信号，不给出买卖指令。",
    namespace = "finance.technical",
    category = "technical_analysis",
    readOnly = true,
    idempotent = true,
    inputSchema = """
      {
        "type":"object",
        "properties":{
          "dataset_id":{"type":"string"},
          "fast_period":{"type":"integer","minimum":2,"default":12},
          "slow_period":{"type":"integer","minimum":3,"default":26},
          "signal_period":{"type":"integer","minimum":2,"default":9},
          "include_series":{"type":"boolean","default":false}
        },
        "required":["dataset_id"]
      }
      """)
public IndicatorToolResponse macdAnalysis(MacdToolRequest request) {
    MacdParameters parameters = request.toValidatedParameters();
    BarDataset dataset = datasetService.require(request.datasetId());
    MacdResult result = technicalAnalysisService.macd(dataset, parameters);
    return IndicatorToolResponse.from(dataset, result);
}
```

实际项目的反射执行器当前支持 `Map<String,Object>` 和 JSON 字符串返回值；第一轮可以保留该适配形式，但 application service 必须使用强类型请求和结果。bootstrap 只负责 `Map -> DTO -> service -> JSON`，不出现指标公式。

### 2.12 单元测试与基准数据

每个指标至少包含四类测试：

1. 公式样例：小型固定数据逐项验证中间结果。
2. Golden dataset：固定 OHLCV CSV，与 ta4j 或另一个可信实现对比，误差建议 `1e-8`，API 舍入测试另做。
3. 性质测试：SMA 恒定序列仍恒定；RSI 始终位于 0–100；BOLL upper>=middle>=lower；ATR>=0。
4. 边界测试：数据不足、恒定价格、零成交量、gap、极大数值、乱序、重复时间、未收盘 Bar。

MACD 必测：

- 单调上涨序列最终 DIF 为正。
- 金叉只在穿越当根产生一次，不能后续每根重复。
- 验证 histogram 未乘 2。

RSI 必测：全涨=100、全跌=0、完全不变=50；第一个有效值位置正确。

BOLL 必测：恒定序列上下中轨相等、bandwidth=0、%B=0.5；标准差明确除以 n。

ATR 必测：无 gap、向上 gap、向下 gap 分别选中正确 TR 分支。

OBV 必测：上涨加量、下跌减量、平盘不变；零均量不除零。

集成验收示例：给定同一 `datasetId` 连续调用全部指标，所有响应的 symbol、interval、range、adjustment、source 和 asOf 必须一致；综合报告引用的 signalId 必须真实存在。

### 2.13 P1：第二期分析能力

- 趋势强度：ADX/DMI（默认 14）；ADX 衡量强度而非方向，常用阈值只作为可配置参考。[DMI/ADX 说明](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/dmi)
- 相对强弱：个股相对沪深 300、标普 500 或所属行业指数的比值与区间收益，避免把 RSI 与“相对市场强弱”混为一谈。[Relative Strength Comparison](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/relative-strength-comparison)
- 支撑阻力：区间高低点、分形、成交密集区；输出价格区域和验证次数，不伪造精确单点。
- K 线形态：吞没、锤头、十字星等，只作为低权重上下文信号。
- 基本面：营收/利润增长、毛利率、ROE、经营现金流、负债率、PE/PB/PS、估值历史分位、同行对比。
- 事件面：公告、财报、分红、回购、减持、监管和新闻；记录来源、发布时间、事件发生时间和去重键。
- 多股票对比与条件选股：统一交易日、币种、复权口径和基准后再比较。

### 2.14 P2：研究与留存能力

- 策略回测：时间范围、基准、手续费、滑点、成交规则、复权方式均显式配置；输出累计收益、年化收益、最大回撤、Sharpe、胜率、交易次数和基准超额。
- 防止回测偏差：禁止未来函数；区分信号时间与成交时间；预留训练/验证/样本外区间；记录退市与成分股历史以降低幸存者偏差。
- 自选股、组合看板、价格/指标/事件预警、每日/每周研究摘要、模拟组合；真实交易和自动下单不属于近期范围。
- 历史表现不保证未来结果，回测页面必须显示成本假设和风险声明。[SEC 投资者教育：历史表现局限](https://www.investor.gov/introduction-investing/general-resources/news-alerts/alerts-bulletins/investor-bulletins/updated-investor-bulletin-how-read-mutual-fund-or-etf-shareholder-report)

## 3. Java 落地架构

### 3.1 模块归属

```text
stockmind-domain
  market/Bar, Symbol, Market, Interval, AdjustmentMode
  indicator/IndicatorRequest, IndicatorPoint, Signal, AnalysisReport
  fundamental/FinancialMetric, ValuationSnapshot
  strategy/StrategyDefinition, BacktestResult

stockmind-application
  MarketDataQueryService
  TechnicalAnalysisService
  FundamentalAnalysisService
  StockResearchService（编排行情+指标+基本面+事件）
  BacktestService

stockmind-infrastructure
  market/MarketDataProvider + 真实供应商适配器
  persistence/BarRepository, IndicatorCache, WatchlistRepository
  news/NewsProvider

stockmind-bootstrap
  StockAnalysisAgentTools（仅做 schema、参数校验、调用 application service）
  REST/SSE DTO 与异常映射
```

不要把计算逻辑堆入任一 `@AgentTool` 组件。工具类只承担模型适配；指标公式和数据访问必须可脱离 Agent 独立单测、批处理和复用。

### 3.2 指标引擎选择

建议使用 `org.ta4j:ta4j-core`，它提供 `BarSeries`、指标、规则、策略和回测模型，适合 JVM 项目。[ta4j 官方项目](https://github.com/ta4j/ta4j)；[指标清单](https://ta4j.github.io/ta4j-wiki/Indicators-Inventory.html)

- 当前项目是 Java 21。ta4j 0.18 将基线升级到 Java 21，0.19 也明确要求 Java 21/Maven 3.9+；实施前锁定具体版本并跑兼容验证，不使用浮动版本。[ta4j changelog](https://github.com/ta4j/ta4j/blob/master/CHANGELOG.md)
- 指标实现通过内部 `IndicatorEngine` 接口隔离 ta4j，避免领域层直接依赖第三方类型。
- 对 MACD/RSI/ATR 另写小型基准实现或 golden dataset 测试，与 ta4j 结果按容差对比。
- 预热期（warm-up/unstable bars）不能返回为有效信号；请求 60 日均线至少要拉取显著多于 60 根有效 bar。

示意接口：

```java
public interface IndicatorEngine {
    MacdResult macd(List<Bar> bars, MacdParameters parameters);
    RsiResult rsi(List<Bar> bars, int period);
    BollingerResult bollinger(List<Bar> bars, int period, BigDecimal deviations);
    AtrResult atr(List<Bar> bars, int period);
    VolumeResult volume(List<Bar> bars, int period);
}
```

### 3.3 Agent 工具契约

不要把整段 OHLCV 在工具间来回传给模型。推荐：

1. `historical_bars` 获取并缓存数据，返回 `datasetId + 摘要 + 可视化序列`。
2. 指标工具以 `datasetId` 为主输入；后端从缓存读取一致的数据集。
3. `technical_summary` 接收 `analysisIds` 聚合结构化结果。
4. 前端从 REST 数据端点读取完整图表序列，Agent SSE 只传文本、卡片描述和资源 ID。

统一响应至少包含：

```json
{
  "symbol": "600519.SH",
  "interval": "1d",
  "range": {"start": "2026-01-01", "end": "2026-07-10"},
  "source": "provider-name",
  "adjustment": "forward",
  "timezone": "Asia/Shanghai",
  "asOf": "2026-07-10T15:00:00+08:00",
  "parameters": {},
  "latest": {},
  "signals": [],
  "warnings": [],
  "dataQuality": {"complete": true, "missingBars": 0, "warmupBars": 60}
}
```

### 3.4 数据与正确性约束

- 证券采用规范 ID：`code + exchange`，不可只用 `600519` 猜市场。
- 交易日历、时区、停牌、拆股分红、复权方式必须贯穿缓存键与响应。
- 行情表建议唯一键：`instrument_id, interval, open_time, adjustment, source_version`。
- 数值存储采用 `DECIMAL`/`BigDecimal`；图表计算可按引擎能力使用 double，但输出前统一精度与非有限值处理。
- 缓存键包括 symbol、market、interval、range、adjustment、provider；日线与实时数据使用不同 TTL。
- 数据供应商必须通过 `MarketDataProvider` 接口隔离。选型需另行确认目标市场、授权、实时/延迟要求和预算；当前 mock 不得混入生产结果。

## 4. Stream Chat 页面重设计

### 4.1 产品形态

桌面端采用全屏三栏工作台，中央仍以对话为核心：

```text
┌──────────┬─────────────────────────────────┬──────────────────────┐
│ 品牌/导航 │ 顶部：标的搜索、市场状态、数据时间 │ 研究上下文/分析详情     │
│ 新建分析  ├─────────────────────────────────┤                      │
│ 会话历史  │ 对话消息                         │ 标的快照              │
│ 自选股    │ - 结论卡                         │ K线 + 指标切换         │
│ 最近标的  │ - 证据/风险折叠区                 │ 关键指标              │
│ 设置      │ - 工具执行进度                   │ 新闻/财报/风险         │
│           ├─────────────────────────────────┤                      │
│           │ 快捷问题 + 固定底部输入框          │                      │
└──────────┴─────────────────────────────────┴──────────────────────┘
```

建议桌面列宽 `248px minmax(480px, 1fr) minmax(340px, 420px)`；`body` 与 `.app-shell` 使用 `width:100%; height:100dvh; overflow:hidden`，移除 `max-width:920px` 和居中外边距。只有左侧会话区、中央消息区、右侧详情内容各自滚动。

响应式规则：

- `>= 1280px`：三栏全开。
- `768–1279px`：左侧缩成图标栏，右侧为可开关抽屉。
- `< 768px`：单列聊天；导航和研究面板用全屏抽屉；输入框固定安全区底部。

### 4.2 功能区

- 顶栏：证券搜索、当前标的、市场/交易状态、行情延迟标签、最后更新时间。
- 左栏：新建分析、会话搜索/重命名/删除、自选股、最近查看、用户偏好入口；`sessionId/userId` 不再作为主界面表单展示，移入开发设置。
- 中栏：欢迎态与示例提示；消息流；结构化结论卡（观点不是买卖指令）、指标卡、证据引用、冲突信号、风险与数据局限；Agent 过程用可折叠时间线显示。
- 输入区：自动增高、停止生成、重新生成、股票代码/时间范围/分析维度 chips、快捷指令（技术面/基本面/新闻/综合/对比）。
- 右栏：标的快照、K 线、成交量、MA/MACD/RSI/BOLL 切换、关键财务指标、事件时间线；点击对话中的证据可定位相应图表点或来源。
- 状态体验：骨架屏、断线重连、取消请求、SSE 错误后重试、空响应恢复、请求耗时、数据过期/缺失/延迟明确提示。
- 可访问性：语义按钮、键盘导航、清晰 focus、`aria-live` 只播报增量状态、涨跌不只靠红绿颜色表达。

### 4.3 前后端契约调整

当前 SSE 已有 `process`、`reply_delta`、`done`、`error`，建议扩展为：

- `status`：阶段、工具名、可读说明、progress。
- `text_delta`：回答文本增量。
- `artifact`：`quote_card`、`indicator_card`、`chart`、`evidence_list`、`risk_card` 的结构化描述。
- `citation`：证据 ID、标题、来源、发布时间、URL/知识库文档定位。
- `done`：messageId、executionId、耗时、数据时间、可用操作。
- `error`：稳定错误码、用户提示、retryable。

必须为 SSE 增加 `requestId/messageId`，防止重试、切换会话或并发提问时把事件写入错误消息。增加 `AbortController` 对应的停止按钮；历史消息由后端持久化，不再只存在当前 DOM 和进程内 `sessionTurns`。

图表建议使用 Apache ECharts（K 线、成交量与指标多 grid 联动）；严格转义 Markdown/富文本，若引入渲染库需配置 HTML 禁用或 sanitizer，避免 XSS。

## 5. 产品路线与验收

### 阶段 0：工程化基线（约 1 周）

- 把股票领域模型、application service、provider 接口从 bootstrap 分离。
- 确认首个目标市场与合规数据源；替换 mock 行情并加入数据时间/复权/质量元数据。
- 建立指标 golden tests、交易日历和统一错误码。

验收：相同数据与参数产生确定结果；数据来源和时间可追溯；mock 数据不会被标成真实行情。

### 阶段 1：技术分析 MVP（约 2 周）

- 历史 K 线、MA/MACD/RSI/BOLL/ATR/量价工具及综合技术报告。
- 全屏三栏 Stream Chat、标的搜索、结构化卡片、K 线/指标图、移动端布局。
- SSE 事件协议、停止/重试、消息 ID、错误态。

验收：6 类指标与基准数据误差在约定容差内；任一结论可定位数据和参数；1366×768 无页面级横向滚动，核心区铺满视口；375px 移动端可完整聊天。

### 阶段 2：综合研究与用户留存（约 2–3 周）

- 基本面、估值、公告/新闻事件、同业/基准对比。
- 会话持久化、自选股、最近查看、研究模板、日报/周报。
- 证据引用与冲突信号解释。

验收：综合报告覆盖技术面、基本面、事件面和风险；过期数据醒目标识；用户能从自选股一键发起预设分析。

### 阶段 3：策略研究（约 2–3 周）

- 可配置筛选器、策略模板、成本敏感回测、模拟组合、预警。
- 回测防偏差检查、参数版本、结果可复现与导出。

验收：费用/滑点/基准/成交时点均可见；同一数据快照和策略版本可复现；任何回测结果均带历史表现局限说明。

## 6. 优先级与下一步实施包

建议下一次开发直接进入“阶段 0 + 阶段 1”的纵向切片：

1. 新建领域模型、`MarketDataProvider`、`TechnicalAnalysisService` 和 ta4j 适配器。
2. 实现一套可替换的数据 provider；先用固定 CSV fixture 做测试，真实 provider 配置化接入。
3. 注册 `historical_bars`、`macd_analysis`、`rsi_analysis`、`bollinger_analysis`、`atr_analysis`、`volume_analysis`、`technical_summary`。
4. 新增独立 REST 图表接口和扩展 SSE artifact 协议。
5. 按三栏方案重写 `stream-chat.html`，先使用原生 HTML/CSS/JS + ECharts，保持当前部署方式不变。
6. 完成单元测试、SSE 契约测试、桌面/平板/手机视觉验收和 XSS/异常数据测试。

首轮不建议同时做真实交易、复杂 AI 选股或数十个指标。先确保数据正确、五类核心指标可解释、页面形成闭环，再扩展数量。
