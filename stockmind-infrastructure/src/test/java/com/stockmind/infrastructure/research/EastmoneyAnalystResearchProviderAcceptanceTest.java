package com.stockmind.infrastructure.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

/** No-framework acceptance test for Eastmoney report forecast parsing. */
public final class EastmoneyAnalystResearchProviderAcceptanceTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/reports", exchange -> respond(exchange,
                "{\"data\":[{\"infoCode\":\"AP123\",\"title\":\"一季报点评\",\"orgSName\":\"测试证券\","
                        + "\"publishDate\":\"2026-05-25 00:00:00.000\",\"emRatingName\":\"买入\","
                        + "\"predictThisYearEps\":\"66.68\",\"predictNextYearEps\":\"69.43\","
                        + "\"predictNextTwoYearEps\":\"72.56\"}]}"));
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/reports";
            var provider = new EastmoneyAnalystResearchProvider(HttpClient.newHttpClient(),
                    new ObjectMapper(), endpoint, 0);
            var reports = provider.loadCompanyReports("600519", 10);
            require(reports.size() == 1, "研报数量解析错误");
            require(reports.getFirst().currentYearEps().doubleValue() == 66.68, "预测EPS解析错误");
            require(reports.getFirst().pdfUrl().contains("AP123"), "研报PDF链接解析错误");
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
