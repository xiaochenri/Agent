package com.stockmind.application.market;

/** 指定标的或时间范围内没有可用行情数据。 */
public class MarketDataNotFoundException extends IllegalArgumentException {

    /**
     * @param message 仅用于内部诊断的错误说明；不应直接进入 Agent Observation
     */
    public MarketDataNotFoundException(String message) {
        super(message);
    }
}
