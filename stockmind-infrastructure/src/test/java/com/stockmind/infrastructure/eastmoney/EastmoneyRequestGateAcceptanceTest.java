package com.stockmind.infrastructure.eastmoney;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** No-framework acceptance checks for cache, backoff and 403 circuit breaking. */
public final class EastmoneyRequestGateAcceptanceTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger okCalls = new AtomicInteger();
        AtomicInteger forbiddenCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200,
                "{\"call\":" + okCalls.incrementAndGet() + "}"));
        server.createContext("/error", exchange -> respond(exchange, 500, "error"));
        server.createContext("/forbidden", exchange -> {
            forbiddenCalls.incrementAndGet();
            respond(exchange, 403, "forbidden");
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            EastmoneyRequestGate cacheGate = new EastmoneyRequestGate(0, 30_000, 60_000, 2_000);
            var first = cacheGate.get(client, request(base + "/ok"));
            var second = cacheGate.get(client, request(base + "/ok"));
            require(first.statusCode() == 200 && !first.cacheHit(), "首次请求不应命中缓存");
            require(second.cacheHit() && okCalls.get() == 1, "相同GET请求未被请求级缓存复用");

            EastmoneyRequestGate circuitGate = new EastmoneyRequestGate(0, 0, 60_000, 2_000);
            require(circuitGate.get(client, request(base + "/forbidden")).statusCode() == 403,
                    "403状态未透传");
            try {
                circuitGate.get(client, request(base + "/forbidden"));
                throw new AssertionError("403后应快速熔断");
            } catch (EastmoneyAccessException expected) {
                require("EASTMONEY_CIRCUIT_OPEN".equals(expected.errorCode()), "熔断错误码不稳定");
            }
            require(forbiddenCalls.get() == 1, "熔断期间不应再次访问端点");

            AtomicLong now = new AtomicLong(1_000);
            AtomicLong slept = new AtomicLong();
            EastmoneyRequestGate backoffGate = new EastmoneyRequestGate(
                    0, 0, 60_000, 2_000, now::get, millis -> {
                        slept.addAndGet(millis);
                        now.addAndGet(millis);
                    });
            require(backoffGate.get(client, request(base + "/error")).statusCode() == 500,
                    "5xx状态未透传");
            backoffGate.get(client, request(base + "/ok?backoff=1"));
            require(slept.get() == 2_000, "5xx后未执行统一退避");
        } finally {
            server.stop(0);
        }
    }

    private static HttpRequest request(String uri) {
        return HttpRequest.newBuilder(URI.create(uri)).GET().build();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
