package com.stockmind.bootstrap.business.tool;

/**
 * 股票业务工具对 Agent 暴露的稳定错误。
 *
 * <p>错误码和公开消息都必须是预定义的，禁止把异常消息、异常类型或动态依赖信息写入
 * Tool Observation。完整异常只应由运行时日志和 Trace 保存。</p>
 */
enum StockToolError {
    SYMBOL_REQUIRED("SYMBOL_REQUIRED", "必须提供有效的股票代码", false),
    KEYWORD_REQUIRED("KEYWORD_REQUIRED", "必须提供新闻检索关键词", false),
    QUERY_REQUIRED("QUERY_REQUIRED", "必须提供公开资料检索内容", false),
    INVALID_TIME_WINDOW("INVALID_TIME_WINDOW", "时间范围或日期格式不合法", false),
    INVALID_REPORT_PERIOD("INVALID_REPORT_PERIOD", "必须提供可识别的财报期间", false),
    INVALID_MARKET_QUERY("INVALID_MARKET_QUERY", "行情查询参数不合法", false),
    INVALID_TECHNICAL_PARAMETERS("INVALID_TECHNICAL_PARAMETERS", "技术指标参数不合法", false),
    INVALID_FACTOR_PROFILE_INPUT("INVALID_FACTOR_PROFILE_INPUT", "股票代码、as_of或投资风格不合法", false),
    INVALID_PRICE("INVALID_PRICE", "价格必须是大于零的有效数值", false),
    EPS_INPUT_INCOMPLETE("EPS_INPUT_INCOMPLETE", "缺少可用 EPS，需提供披露 EPS 或净利润与总股本", false),
    EPS_INVALID("EPS_INVALID", "EPS 无效或为零，无法计算 PE", false),
    PE_CALCULATION_INVALID("PE_CALCULATION_INVALID", "PE 计算结果无效", false),
    PE_BASIS_REQUIRED("PE_BASIS_REQUIRED", "非年报EPS不能直接计算年度PE，必须明确原始期间、年化或TTM口径", false),
    PE_BASIS_INCOMPATIBLE("PE_BASIS_INCOMPATIBLE", "当前EPS口径与PE口径不兼容，季度EPS不能作为TTM或静态年度EPS", false),
    MARKET_DATA_NOT_FOUND("MARKET_DATA_NOT_FOUND", "指定股票或时间范围内没有可用行情数据", false),
    KNOWLEDGE_NOT_FOUND("KNOWLEDGE_NOT_FOUND", "未检索到匹配的公开资料", false),
    REPORT_NOT_FOUND("REPORT_NOT_FOUND", "未找到指定股票和期间的财报证据", false),
    MARKET_DATA_UNAVAILABLE("MARKET_DATA_UNAVAILABLE", "行情数据服务暂时不可用", true),
    NEWS_SERVICE_UNAVAILABLE("NEWS_SERVICE_UNAVAILABLE", "新闻数据服务暂时不可用", true),
    ANNOUNCEMENT_SERVICE_UNAVAILABLE("ANNOUNCEMENT_SERVICE_UNAVAILABLE", "公告数据服务暂时不可用", true),
    SECTOR_SERVICE_UNAVAILABLE("SECTOR_SERVICE_UNAVAILABLE", "行业板块数据服务暂时不可用", true),
    RESEARCH_SERVICE_UNAVAILABLE("RESEARCH_SERVICE_UNAVAILABLE", "机构研报数据服务暂时不可用", true),
    DIVIDEND_SERVICE_UNAVAILABLE("DIVIDEND_SERVICE_UNAVAILABLE", "分红数据服务暂时不可用", true),
    // The comparison call aggregates the same sector dependency on every attempt. Retrying the
    // identical request cannot recover a missing industry mapping or an open provider circuit.
    VALUATION_COMPARISON_UNAVAILABLE("VALUATION_COMPARISON_UNAVAILABLE", "同行估值比较服务暂时不可用", false),
    TECHNICAL_ANALYSIS_UNAVAILABLE("TECHNICAL_ANALYSIS_UNAVAILABLE", "技术分析服务暂时不可用", true),
    KNOWLEDGE_STORE_UNAVAILABLE("KNOWLEDGE_STORE_UNAVAILABLE", "公开资料接口暂时不可用", true),
    FINANCIAL_REPORT_SERVICE_UNAVAILABLE(
            "FINANCIAL_REPORT_SERVICE_UNAVAILABLE", "财报数据服务暂时不可用", true),
    FACTOR_PROFILE_UNAVAILABLE("FACTOR_PROFILE_UNAVAILABLE", "股票因子画像暂时不可用", false);

    private final String code;
    private final String publicMessage;
    private final boolean retryable;

    StockToolError(String code, String publicMessage, boolean retryable) {
        this.code = code;
        this.publicMessage = publicMessage;
        this.retryable = retryable;
    }

    String code() {
        return code;
    }

    String publicMessage() {
        return publicMessage;
    }

    boolean retryable() {
        return retryable;
    }
}
