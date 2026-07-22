package com.stockmind.infrastructure.eastmoney;

public record EastmoneyGatewayResponse(int statusCode, String body, boolean cacheHit) {
}
