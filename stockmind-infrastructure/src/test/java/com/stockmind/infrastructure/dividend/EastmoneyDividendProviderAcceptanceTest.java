package com.stockmind.infrastructure.dividend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

/** No-framework acceptance test for per-ten-shares dividend parsing. */
public final class EastmoneyDividendProviderAcceptanceTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/dividends", exchange -> respond(exchange,
                "{\"result\":{\"data\":[{\"EX_DIVIDEND_DATE\":\"2026-06-26 00:00:00\","
                        + "\"PRETAX_BONUS_RMB\":280.2423,\"ASSIGN_PROGRESS\":\"实施分配\"}]}}"));
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/dividends";
            var provider = new EastmoneyDividendProvider(HttpClient.newHttpClient(),
                    new ObjectMapper(), endpoint, 0);
            var records = provider.loadHistory("600519", 10);
            require(records.size() == 1, "分红数量解析错误");
            require(records.getFirst().cashPerTenShares().doubleValue() == 280.2423, "每10股派息解析错误");
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
