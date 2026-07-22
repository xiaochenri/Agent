package com.stockmind.infrastructure.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.application.research.AnalystReport;
import com.stockmind.application.research.AnalystResearchProvider;
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

/** Eastmoney public company-research API adapter. */
@Component
public class EastmoneyAnalystResearchProvider implements AnalystResearchProvider {
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final EastmoneyRequestGate requestGate;

    @Autowired
    public EastmoneyAnalystResearchProvider(
            @Value("${stockmind.research.eastmoney.url:https://reportapi.eastmoney.com/report/list}") String endpoint,
            EastmoneyRequestGate requestGate) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(),
                endpoint, requestGate);
    }

    EastmoneyAnalystResearchProvider(HttpClient httpClient, ObjectMapper objectMapper,
                                     String endpoint, long minimumIntervalMillis) {
        this(httpClient, objectMapper, endpoint,
                new EastmoneyRequestGate(minimumIntervalMillis, 0, 60_000, 2_000));
    }

    EastmoneyAnalystResearchProvider(HttpClient httpClient, ObjectMapper objectMapper,
                                     String endpoint, EastmoneyRequestGate requestGate) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.requestGate = requestGate;
    }

    @Override
    public List<AnalystReport> loadCompanyReports(String symbol, int limit) {
        String code = normalize(symbol);
        int pageSize = Math.max(1, Math.min(100, limit));
        String query = "industryCode=*&pageSize=" + pageSize
                + "&industry=*&rating=*&ratingChange=*&beginTime=2000-01-01&endTime=2030-01-01"
                + "&pageNo=1&fields=&qType=0&orgCode=&code=" + encode(code) + "&rcode=";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", USER_AGENT)
                    .header("Referer", "https://data.eastmoney.com/").GET().build();
            var response = requestGate.get(httpClient, request);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("东财研报接口响应状态 " + response.statusCode());
            JsonNode rows = objectMapper.readTree(response.body()).path("data");
            if (!rows.isArray()) return List.of();
            List<AnalystReport> result = new ArrayList<>();
            for (JsonNode row : rows) {
                LocalDate published = date(row.path("publishDate").asText());
                if (published == null) continue;
                String infoCode = row.path("infoCode").asText();
                result.add(new AnalystReport(infoCode, code, row.path("title").asText(),
                        row.path("orgSName").asText(), published, row.path("emRatingName").asText(),
                        decimal(row.path("predictThisYearEps")), decimal(row.path("predictNextYearEps")),
                        decimal(row.path("predictNextTwoYearEps")), infoCode.isBlank() ? ""
                        : "https://pdf.dfcfw.com/pdf/H3_" + infoCode + "_1.pdf"));
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("东财研报接口调用失败", e);
        }
    }

    private String normalize(String value) {
        String code = value == null ? "" : value.trim().toUpperCase()
                .replaceFirst("^(SH|SZ|BJ)", "").replaceFirst("\\.(SH|SZ|BJ)$", "");
        if (!code.matches("\\d{6}")) throw new IllegalArgumentException("研报查询仅支持6位A股代码");
        return code;
    }

    private LocalDate date(String value) {
        try { return LocalDate.parse(value.substring(0, 10)); }
        catch (Exception e) { return null; }
    }

    private BigDecimal decimal(JsonNode value) {
        try {
            String text = value.asText().trim();
            return text.isBlank() || "-".equals(text) ? null : new BigDecimal(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
