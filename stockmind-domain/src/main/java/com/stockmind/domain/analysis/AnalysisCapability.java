package com.stockmind.domain.analysis;

/**
 * 业务证据能力标识。
 *
 * <p>领域层只描述需要哪类能力，不依赖具体工具名称；工具适配层负责把能力映射到当前可用工具。</p>
 */
public enum AnalysisCapability {
    FORWARD_VALUATION,
    SCENARIO_VALUATION,
    FINANCIAL_TREND,
    PEER_VALUATION_CONTEXT,
    STRICT_PEER_VALIDATION,
    EARNINGS_ATTRIBUTION,
    RESEARCH_CONTEXT,
    EVENT_DISCOVERY,
    PRIMARY_EVENT_VERIFICATION
}
