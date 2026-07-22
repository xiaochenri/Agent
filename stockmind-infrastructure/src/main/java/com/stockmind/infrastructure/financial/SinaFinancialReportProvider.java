package com.stockmind.infrastructure.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.application.financial.FinancialReportProvider;
import com.stockmind.application.financial.FinancialStatementPeriod;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Sina public API adapter for balance sheet, income statement and cash-flow statement. */
@Component
public class SinaFinancialReportProvider implements FinancialReportProvider {
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;

    @Autowired
    public SinaFinancialReportProvider(
            @Value("${stockmind.financial.sina.url:https://quotes.sina.cn/cn/api/openapi.php/CompanyFinanceService.getFinanceReport2022}") String endpoint) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(), endpoint);
    }

    SinaFinancialReportProvider(HttpClient httpClient, ObjectMapper objectMapper, String endpoint) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
    }

    @Override
    public List<FinancialStatementPeriod> load(String symbol, String statementType, int periods) {
        String code = normalize(symbol);
        String type = normalizeType(statementType);
        String paperCode = (code.startsWith("6") ? "sh" : "sz") + code;
        String query = "paperCode=" + encode(paperCode) + "&source=" + type
                + "&type=0&page=1&num=" + Math.max(1, Math.min(20, periods));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("新浪财报接口响应状态 " + response.statusCode());
            }
            JsonNode reports = objectMapper.readTree(response.body()).path("result").path("data").path("report_list");
            if (!reports.isObject()) return List.of();
            List<FinancialStatementPeriod> result = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = reports.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                LocalDate period = LocalDate.parse(field.getKey(), PERIOD);
                String rawPublishedDate = field.getValue().path("publish_date").asText();
                // A missing disclosure date cannot be replaced with report_period: doing so would
                // make an unpublished statement appear available in historical point-in-time queries.
                if (!rawPublishedDate.matches("\\d{8}")) continue;
                LocalDate publishedDate = LocalDate.parse(rawPublishedDate, PERIOD);
                Map<String, String> values = new LinkedHashMap<>();
                Map<String, String> yoy = new LinkedHashMap<>();
                JsonNode rows = field.getValue().path("data");
                if (rows.isArray()) {
                    for (JsonNode row : rows) {
                        String title = row.path("item_title").asText();
                        if (title.isBlank() || row.path("item_value").isNull()) continue;
                        values.put(title, row.path("item_value").asText());
                        String yearOverYear = row.path("item_tongbi").asText();
                        if (!yearOverYear.isBlank()) yoy.put(title, yearOverYear);
                    }
                }
                result.add(new FinancialStatementPeriod(period, publishedDate, type,
                        Map.copyOf(values), Map.copyOf(yoy)));
            }
            result.sort(java.util.Comparator.comparing(FinancialStatementPeriod::reportPeriod).reversed());
            return List.copyOf(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("新浪财报接口调用失败", e);
        }
    }

    private String normalize(String value) {
        String code = value == null ? "" : value.trim().toUpperCase()
                .replaceFirst("^(SH|SZ)", "").replaceFirst("\\.(SH|SZ)$", "");
        if (!code.matches("\\d{6}") || code.startsWith("8") || code.startsWith("4")) {
            throw new IllegalArgumentException("新浪财报当前仅支持沪深6位股票代码");
        }
        return code;
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase();
        if (!List.of("lrb", "fzb", "llb").contains(type)) {
            throw new IllegalArgumentException("statementType仅支持lrb/fzb/llb");
        }
        return type;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
