package com.stockmind.infrastructure.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.application.market.HistoricalBarsQuery;
import com.stockmind.domain.market.AdjustmentMode;
import com.stockmind.domain.market.BarInterval;
import com.stockmind.domain.market.MarketQuote;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.time.LocalDate;

/** No-framework parser acceptance test for Tencent quote and K-line payloads. */
public final class TencentMarketDataProviderAcceptanceTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/quote", exchange -> {
            String[] fields = new String[33];
            java.util.Arrays.fill(fields, "");
            fields[0] = "1";
            fields[1] = "贵州茅台";
            fields[2] = "600519";
            fields[3] = "1253.00";
            fields[4] = "1258.99";
            fields[5] = "1269.01";
            fields[6] = "58417";
            fields[30] = "20260717161459";
            fields[31] = "-5.99";
            fields[32] = "-0.48";
            String body = "v_sh600519=\"" + String.join("~", fields) + "\";";
            byte[] bytes = body.getBytes(Charset.forName("GBK"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/kline", exchange -> {
            String body = "{\"code\":0,\"data\":{\"sh600519\":{\"qfqday\":["
                    + "[\"2026-07-16\",\"1252.00\",\"1258.99\",\"1267.97\",\"1245.05\",\"47611.00\"],"
                    + "[\"2026-07-17\",\"1269.01\",\"1253.00\",\"1269.33\",\"1238.98\",\"58417.00\"]]}}}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            TencentMarketDataProvider provider = new TencentMarketDataProvider(
                    HttpClient.newHttpClient(), new ObjectMapper(), base + "/quote?q=", base + "/kline");
            MarketQuote quote = provider.loadQuote("600519");
            require(quote.price().doubleValue() == 1253.0, "实时价格解析错误");
            require(quote.dailyChangePct().doubleValue() == -0.48, "单日涨跌幅解析错误");
            var bars = provider.loadBars(new HistoricalBarsQuery("600519", BarInterval.DAY_1,
                    LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 17), AdjustmentMode.FORWARD));
            require(bars.bars().size() == 2, "K线数量解析错误");
            require(bars.bars().getLast().close().doubleValue() == 1253.0, "K线收盘价解析错误");
        } finally {
            server.stop(0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
