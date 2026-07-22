package com.stockmind.infrastructure.sector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.infrastructure.eastmoney.EastmoneyRequestGate;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

/** No-framework acceptance test for sector membership intersection. */
public final class EastmoneySectorDataProviderAcceptanceTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/membership", exchange -> respond(exchange,
                "{\"data\":{\"diff\":[{\"f12\":\"BK0477\",\"f14\":\"酿酒行业\",\"f3\":1.25,\"f128\":\"贵州茅台\"},"
                        + "{\"f12\":\"BK0816\",\"f14\":\"贵州板块\",\"f3\":0.5}]}}"));
        server.createContext("/profile", exchange -> respond(exchange,
                "{\"data\":{\"f57\":\"600519\",\"f58\":\"贵州茅台\",\"f127\":\"酿酒行业\"}}"));
        server.createContext("/industries", exchange -> {
            boolean constituents = exchange.getRequestURI().getRawQuery().contains("b%3ABK0477");
            respond(exchange, constituents
                    ? "{\"data\":{\"diff\":[{\"f12\":\"600519\",\"f14\":\"贵州茅台\",\"f20\":1500000000000},"
                    + "{\"f12\":\"200596\",\"f14\":\"古井贡B\",\"f20\":600000000000},"
                    + "{\"f12\":\"000858\",\"f14\":\"五粮液\",\"f20\":500000000000}]}}"
                    : "{\"data\":{\"diff\":[{\"f12\":\"BK0477\",\"f14\":\"酿酒行业\",\"f3\":1.25}]}}");
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var provider = new EastmoneySectorDataProvider(HttpClient.newHttpClient(), new ObjectMapper(),
                    base + "/membership", base + "/profile", base + "/industries",
                    new EastmoneyRequestGate(0, 0, 60_000, 2_000));
            var sectors = provider.loadIndustrySectors("600519");
            require(sectors.size() == 1, "行业交集解析错误");
            require("酿酒行业".equals(sectors.getFirst().name()), "行业名称解析错误");
            var peers = provider.loadTopIndustryConstituents("600519", 10);
            require(peers.constituents().size() == 2, "行业成分股解析错误");
            require("SH600519".equals(peers.constituents().getFirst().normalizedSymbol()), "行业成分股规范化错误");
            require(peers.constituents().stream().noneMatch(c -> c.normalizedSymbol().contains("200596")),
                    "B股不应进入A股同行样本");
        } finally { server.stop(0); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
