package com.stockmind.infrastructure.announcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.application.announcement.AnnouncementProvider;
import com.stockmind.application.announcement.CompanyAnnouncement;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** CNINFO public API adapter for official listed-company announcements. */
@Component
public class CninfoAnnouncementProvider implements AnnouncementProvider {
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String mappingUrl;
    private final String queryUrl;
    private final Map<String, String> orgIds = new ConcurrentHashMap<>();

    @Autowired
    public CninfoAnnouncementProvider(
            @Value("${stockmind.announcement.cninfo.mapping-url:https://www.cninfo.com.cn/new/data/szse_stock.json}") String mappingUrl,
            @Value("${stockmind.announcement.cninfo.query-url:https://www.cninfo.com.cn/new/hisAnnouncement/query}") String queryUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), new ObjectMapper(), mappingUrl, queryUrl);
    }

    CninfoAnnouncementProvider(HttpClient httpClient, ObjectMapper objectMapper,
                               String mappingUrl, String queryUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.mappingUrl = mappingUrl;
        this.queryUrl = queryUrl;
    }

    @Override
    public List<CompanyAnnouncement> search(String symbol, LocalDate startDate, LocalDate endDate, int limit) {
        String code = normalize(symbol);
        try {
            String orgId = resolveOrgId(code);
            String form = form(Map.ofEntries(
                    Map.entry("stock", code + "," + orgId), Map.entry("tabName", "fulltext"),
                    Map.entry("pageSize", String.valueOf(Math.max(1, Math.min(100, limit)))),
                    Map.entry("pageNum", "1"), Map.entry("column", ""), Map.entry("category", ""),
                    Map.entry("plate", ""), Map.entry("seDate", startDate + "~" + endDate),
                    Map.entry("searchkey", ""), Map.entry("secid", ""), Map.entry("sortName", ""),
                    Map.entry("sortType", ""), Map.entry("isHLtitle", "true")));
            HttpRequest request = HttpRequest.newBuilder(URI.create(queryUrl)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Referer", "https://www.cninfo.com.cn/new/disclosure")
                    .header("Origin", "https://www.cninfo.com.cn")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response.statusCode(), "公告查询");
            JsonNode items = objectMapper.readTree(response.body()).path("announcements");
            if (!items.isArray()) return List.of();
            List<CompanyAnnouncement> result = new ArrayList<>();
            for (JsonNode item : items) {
                LocalDate date = publishedDate(item.path("announcementTime"));
                if (date == null || date.isBefore(startDate) || date.isAfter(endDate)) continue;
                String id = item.path("announcementId").asText();
                String adjunct = item.path("adjunctUrl").asText();
                result.add(new CompanyAnnouncement(id, cleanHtml(item.path("announcementTitle").asText()),
                        item.path("announcementTypeName").asText(), date,
                        "https://www.cninfo.com.cn/new/disclosure/detail?annoId=" + id,
                        adjunct.isBlank() ? "" : "https://static.cninfo.com.cn/" + adjunct));
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("巨潮公告接口调用失败", e);
        }
    }

    private String resolveOrgId(String code) throws Exception {
        if (orgIds.isEmpty()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(mappingUrl)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response.statusCode(), "股票映射");
            JsonNode stocks = objectMapper.readTree(response.body()).path("stockList");
            if (stocks.isArray()) {
                for (JsonNode stock : stocks) orgIds.put(stock.path("code").asText(), stock.path("orgId").asText());
            }
        }
        return orgIds.getOrDefault(code, fallbackOrgId(code));
    }

    private String fallbackOrgId(String code) {
        if (code.startsWith("6")) return "gssh0" + code;
        if (code.startsWith("8") || code.startsWith("4")) return "gsbj0" + code;
        return "gssz0" + code;
    }

    private String normalize(String symbol) {
        String code = symbol == null ? "" : symbol.trim().toUpperCase()
                .replaceFirst("^(SH|SZ|BJ)", "").replaceFirst("\\.(SH|SZ|BJ)$", "");
        if (!code.matches("\\d{6}")) throw new IllegalArgumentException("公告查询仅支持6位A股代码");
        return code;
    }

    private LocalDate publishedDate(JsonNode value) {
        try {
            if (value.isNumber()) return Instant.ofEpochMilli(value.asLong()).atZone(CHINA_ZONE).toLocalDate();
            String text = value.asText();
            return text.length() >= 10 ? LocalDate.parse(text.substring(0, 10)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String cleanHtml(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", "").trim();
    }

    private void ensureSuccess(int status, String operation) {
        if (status < 200 || status >= 300) throw new IllegalStateException(operation + "响应状态 " + status);
    }
}
