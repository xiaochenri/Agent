package com.stockmind.infrastructure.dividend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.application.dividend.DividendDistribution;
import com.stockmind.application.dividend.DividendProvider;
import com.stockmind.infrastructure.eastmoney.EastmoneyRequestGate;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Eastmoney datacenter adapter for implemented cash dividends. */
@Component
public class EastmoneyDividendProvider implements DividendProvider {
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final EastmoneyRequestGate requestGate;

    @Autowired
    public EastmoneyDividendProvider(
            @Value("${stockmind.dividend.eastmoney.url:https://datacenter-web.eastmoney.com/api/data/v1/get}") String endpoint,
            EastmoneyRequestGate requestGate) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(),
                endpoint, requestGate);
    }

    EastmoneyDividendProvider(HttpClient httpClient, ObjectMapper objectMapper,
                              String endpoint, long minimumIntervalMillis) {
        this(httpClient, objectMapper, endpoint,
                new EastmoneyRequestGate(minimumIntervalMillis, 0, 60_000, 2_000));
    }

    EastmoneyDividendProvider(HttpClient httpClient, ObjectMapper objectMapper,
                              String endpoint, EastmoneyRequestGate requestGate) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.requestGate = requestGate;
    }

    @Override
    public List<DividendDistribution> loadHistory(String symbol, int limit) {
        String code = normalize(symbol);
        int pageSize = Math.max(1, Math.min(100, limit));
        String query = "reportName=RPT_SHAREBONUS_DET&columns=ALL&filter="
                + encode("(SECURITY_CODE=\"" + code + "\")")
                + "&pageNumber=1&pageSize=" + pageSize
                + "&sortColumns=EX_DIVIDEND_DATE&sortTypes=-1&source=WEB&client=WEB";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", USER_AGENT)
                    .header("Referer", "https://data.eastmoney.com/").GET().build();
            var response = requestGate.get(httpClient, request);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("东财分红接口响应状态 " + response.statusCode());
            JsonNode rows = objectMapper.readTree(response.body()).path("result").path("data");
            if (!rows.isArray()) return List.of();
            List<DividendDistribution> result = new ArrayList<>();
            for (JsonNode row : rows) {
                LocalDate date = date(row.path("EX_DIVIDEND_DATE").asText());
                BigDecimal cash = decimal(row.path("PRETAX_BONUS_RMB"));
                if (date != null && cash != null)
                    result.add(new DividendDistribution(date, cash, row.path("ASSIGN_PROGRESS").asText()));
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("东财分红接口调用失败", e);
        }
    }

    private String normalize(String value) {
        String code = value == null ? "" : value.trim().toUpperCase()
                .replaceFirst("^(SH|SZ|BJ)", "").replaceFirst("\\.(SH|SZ|BJ)$", "");
        if (!code.matches("\\d{6}")) throw new IllegalArgumentException("分红查询仅支持6位A股代码");
        return code;
    }

    private LocalDate date(String value) {
        try { return LocalDate.parse(value.substring(0, 10)); }
        catch (Exception e) { return null; }
    }

    private BigDecimal decimal(JsonNode value) {
        try { return new BigDecimal(value.asText()); }
        catch (Exception e) { return null; }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
