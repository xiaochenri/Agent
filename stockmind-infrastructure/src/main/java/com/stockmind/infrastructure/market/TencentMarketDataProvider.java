package com.stockmind.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.application.market.HistoricalBarsQuery;
import com.stockmind.application.market.MarketDataNotFoundException;
import com.stockmind.application.market.MarketDataProvider;
import com.stockmind.domain.market.AdjustmentMode;
import com.stockmind.domain.market.BarDataset;
import com.stockmind.domain.market.MarketBar;
import com.stockmind.domain.market.MarketQuote;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Tencent Finance HTTP adapter for real-time A-share quotes and adjusted daily K-lines. */
@Component
public class TencentMarketDataProvider implements MarketDataProvider {
    static final String SOURCE = "tencent_finance_api";
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Charset GBK = Charset.forName("GBK");
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String quoteUrl;
    private final String klineUrl;

    @Autowired
    public TencentMarketDataProvider(
            @Value("${stockmind.market.tencent.quote-url:https://qt.gtimg.cn/q=}") String quoteUrl,
            @Value("${stockmind.market.tencent.kline-url:https://web.ifzq.gtimg.cn/appstock/app/fqkline/get}") String klineUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(), quoteUrl, klineUrl);
    }

    TencentMarketDataProvider(HttpClient httpClient, ObjectMapper objectMapper, String quoteUrl, String klineUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.quoteUrl = quoteUrl;
        this.klineUrl = klineUrl;
    }

    @Override
    public MarketQuote loadQuote(String instrumentId) {
        String symbol = normalizeSymbol(instrumentId);
        String providerSymbol = providerSymbol(symbol);
        try {
            HttpRequest request = request(URI.create(quoteUrl + providerSymbol)).build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("腾讯实时行情接口响应状态 " + response.statusCode());
            }
            byte[] bytes = response.body();
            String payload = new String(bytes, GBK);
            int firstQuote = payload.indexOf('"');
            int lastQuote = payload.lastIndexOf('"');
            if (firstQuote < 0 || lastQuote <= firstQuote) throw notFound(symbol);
            String[] values = payload.substring(firstQuote + 1, lastQuote).split("~", -1);
            if (values.length < 33 || values[3].isBlank()) throw notFound(symbol);
            BigDecimal price = decimal(values[3]);
            BigDecimal previousClose = decimal(values[4]);
            BigDecimal changePct = values[32].isBlank()
                    ? previousClose.signum() == 0 ? BigDecimal.ZERO
                    : price.subtract(previousClose).multiply(BigDecimal.valueOf(100))
                            .divide(previousClose, 6, java.math.RoundingMode.HALF_UP)
                    : decimal(values[32]);
            Instant asOf = values[30].isBlank() ? Instant.now()
                    : LocalDateTime.parse(values[30], QUOTE_TIME).atZone(CHINA_ZONE).toInstant();
            return new MarketQuote(symbol, datasetId("quote", symbol, values[30]), SOURCE, asOf,
                    price, previousClose, changePct, decimal(values[6]),
                    List.of("腾讯成交量字段单位为手（1手=100股）。"));
        } catch (MarketDataNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("腾讯实时行情接口调用失败", e);
        }
    }

    @Override
    public BarDataset loadBars(HistoricalBarsQuery query) {
        String symbol = normalizeSymbol(query.instrumentId());
        String providerSymbol = providerSymbol(symbol);
        String adjustment = adjustmentParameter(query.adjustment());
        String dataKey = adjustment.isBlank() ? "day" : adjustment + "day";
        long requestedDays = ChronoUnit.DAYS.between(query.startDate(), query.endDate()) + 30;
        long count = Math.max(80, Math.min(1000, requestedDays));
        String param = String.join(",", providerSymbol, "day", query.startDate().toString(),
                query.endDate().toString(), String.valueOf(count)) + (adjustment.isBlank() ? "" : "," + adjustment);
        URI uri = URI.create(klineUrl + "?param=" + URLEncoder.encode(param, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = httpClient.send(request(uri).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("腾讯历史行情接口响应状态 " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode rows = root.path("data").path(providerSymbol).path(dataKey);
            if (!rows.isArray()) throw notFound(symbol);
            List<MarketBar> bars = new ArrayList<>();
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 6) continue;
                LocalDate date = LocalDate.parse(row.get(0).asText());
                if (date.isBefore(query.startDate()) || date.isAfter(query.endDate())) continue;
                Instant openTime = date.atTime(LocalTime.of(9, 30)).atZone(CHINA_ZONE).toInstant();
                Instant closeTime = date.atTime(LocalTime.of(15, 0)).atZone(CHINA_ZONE).toInstant();
                bars.add(new MarketBar(symbol, query.interval(), openTime, closeTime,
                        decimal(row.get(1).asText()), decimal(row.get(3).asText()),
                        decimal(row.get(4).asText()), decimal(row.get(2).asText()),
                        decimal(row.get(5).asText()), BigDecimal.ZERO, query.adjustment()));
            }
            if (bars.isEmpty()) throw notFound(symbol);
            return new BarDataset(datasetId("bars", symbol,
                    query.startDate() + "|" + query.endDate() + "|" + query.adjustment()),
                    List.copyOf(bars), SOURCE, Instant.now(),
                    List.of("腾讯日K成交量单位为手（1手=100股）。", "腾讯日K接口不返回历史成交额，amount 字段记为0。"));
        } catch (MarketDataNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("腾讯历史行情接口调用失败", e);
        }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT).GET();
    }

    static String normalizeSymbol(String value) {
        String symbol = value == null ? "" : value.trim().toUpperCase();
        symbol = symbol.replaceFirst("^(SH|SZ|BJ)", "").replaceFirst("\\.(SH|SZ|BJ)$", "");
        if (!symbol.matches("\\d{6}")) {
            throw new IllegalArgumentException("腾讯行情当前仅支持6位A股、指数和ETF代码");
        }
        return symbol;
    }

    static String providerSymbol(String symbol) {
        if (symbol.startsWith("6") || symbol.startsWith("9")) return "sh" + symbol;
        if (symbol.startsWith("8") || symbol.startsWith("4")) return "bj" + symbol;
        return "sz" + symbol;
    }

    private String adjustmentParameter(AdjustmentMode mode) {
        return switch (mode) {
            case FORWARD -> "qfq";
            case BACKWARD -> "hfq";
            case NONE -> "";
        };
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private MarketDataNotFoundException notFound(String symbol) {
        return new MarketDataNotFoundException("腾讯接口未返回 " + symbol + " 的行情数据");
    }

    private String datasetId(String type, String symbol, String qualifier) {
        String value = SOURCE + "|" + type + "|" + symbol + "|" + qualifier;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
