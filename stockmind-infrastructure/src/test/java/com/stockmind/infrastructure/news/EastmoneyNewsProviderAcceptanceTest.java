package com.stockmind.infrastructure.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** No-framework parser acceptance test for the Eastmoney JSONP news payload. */
public final class EastmoneyNewsProviderAcceptanceTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/news", exchange -> {
            String body = "jQuery_stockmind_news({\"code\":0,\"result\":{\"cmsArticleWebOld\":[{"
                    + "\"date\":\"2026-07-18 12:00:00\",\"code\":\"noise\","
                    + "\"title\":\"其他公司价格调整\",\"content\":\"市场出现价格变化。\","
                    + "\"mediaName\":\"测试媒体\",\"url\":\"https://example.test/noise\"},{"
                    + "\"date\":\"2026-07-17 21:43:00\",\"code\":\"article-1\","
                    + "\"title\":\"<b>贵州茅台</b>价格调整\",\"content\":\"公司发布价格调整公告。\","
                    + "\"mediaName\":\"证券时报\",\"url\":\"https://example.test/article-1\"}]}})";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/news";
            EastmoneyNewsProvider provider = new EastmoneyNewsProvider(
                    HttpClient.newHttpClient(), new ObjectMapper(), endpoint, 0);
            var articles = provider.search("贵州茅台 价格调整", LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 19), 10);
            require(articles.size() == 1, "无关公司新闻未被相关性门槛过滤");
            require("贵州茅台价格调整".equals(articles.getFirst().title()), "新闻标题清洗错误");
            require("证券时报".equals(articles.getFirst().source()), "新闻来源解析错误");
        } finally {
            server.stop(0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
