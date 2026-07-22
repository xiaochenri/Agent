package com.stockmind.infrastructure.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

/** No-framework acceptance test for Sina structured financial statements. */
public final class SinaFinancialReportProviderAcceptanceTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/finance", exchange -> {
            String body = "{\"result\":{\"data\":{\"report_list\":{\"20260331\":{\"publish_date\":\"20260425\",\"data\":["
                    + "{\"item_title\":\"营业总收入\",\"item_value\":\"50000000000\",\"item_tongbi\":\"0.085\"},"
                    + "{\"item_title\":\"基本每股收益\",\"item_value\":\"12.34\"}]}}}}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/finance";
            var provider = new SinaFinancialReportProvider(HttpClient.newHttpClient(), new ObjectMapper(), endpoint);
            var reports = provider.load("600519", "lrb", 8);
            require(reports.size() == 1, "财报期次解析错误");
            require("2026-04-25".equals(reports.getFirst().publishedDate().toString()), "披露日期解析错误");
            require("12.34".equals(reports.getFirst().values().get("基本每股收益")), "EPS解析错误");
            require("0.085".equals(reports.getFirst().yearOverYearValues().get("营业总收入")), "同比小数比例解析错误");
        } finally { server.stop(0); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
