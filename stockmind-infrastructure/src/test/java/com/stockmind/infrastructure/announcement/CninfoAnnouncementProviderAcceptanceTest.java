package com.stockmind.infrastructure.announcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** No-framework acceptance test for CNINFO mapping and announcement payloads. */
public final class CninfoAnnouncementProviderAcceptanceTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mapping", exchange -> respond(exchange,
                "{\"stockList\":[{\"code\":\"600519\",\"orgId\":\"gssh0600519\"}]}"));
        server.createContext("/query", exchange -> respond(exchange,
                "{\"announcements\":[{\"announcementId\":\"anno-1\","
                        + "\"announcementTitle\":\"<em>贵州茅台</em>年度报告\","
                        + "\"announcementTypeName\":\"年度报告\",\"announcementTime\":1784304000000,"
                        + "\"adjunctUrl\":\"finalpage/2026-07-18/report.pdf\"}]}"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var provider = new CninfoAnnouncementProvider(HttpClient.newHttpClient(), new ObjectMapper(),
                    base + "/mapping", base + "/query");
            var items = provider.search("600519", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 10);
            require(items.size() == 1, "公告数量解析错误");
            require("贵州茅台年度报告".equals(items.getFirst().title()), "公告标题清洗错误");
            require(items.getFirst().pdfUrl().contains("report.pdf"), "公告PDF链接解析错误");
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
