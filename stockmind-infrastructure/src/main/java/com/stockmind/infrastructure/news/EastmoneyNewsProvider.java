package com.stockmind.infrastructure.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.application.news.NewsArticle;
import com.stockmind.application.news.NewsProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Eastmoney public search API adapter for stock-related news. */
@Component
public class EastmoneyNewsProvider implements NewsProvider {
    private static final String CALLBACK = "jQuery_stockmind_news";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final DateTimeFormatter PUBLISHED_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final long minimumIntervalMillis;
    private final Object throttleLock = new Object();
    private long lastRequestAt;

    @Autowired
    public EastmoneyNewsProvider(
            @Value("${stockmind.news.eastmoney.url:https://search-api-web.eastmoney.com/search/jsonp}") String endpoint,
            @Value("${stockmind.news.eastmoney.minimum-interval-millis:1200}") long minimumIntervalMillis) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(),
                endpoint, minimumIntervalMillis);
    }

    EastmoneyNewsProvider(HttpClient httpClient, ObjectMapper objectMapper, String endpoint,
                          long minimumIntervalMillis) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.minimumIntervalMillis = Math.max(0, minimumIntervalMillis);
    }

    @Override
    public List<NewsArticle> search(String keyword, LocalDate startDate, LocalDate endDate, int limit) {
        if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("keyword 不能为空");
        int pageSize = Math.max(1, Math.min(100, limit));
        try {
            String inner = objectMapper.writeValueAsString(buildRequest(keyword.trim(), pageSize));
            String query = "cb=" + encode(CALLBACK) + "&param=" + encode(inner);
            throttle();
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://so.eastmoney.com/")
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("东财新闻接口响应状态 " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(unwrapJsonp(response.body()));
            JsonNode articles = root.path("result").path("cmsArticleWebOld");
            if (!articles.isArray()) return List.of();
            List<NewsArticle> results = new ArrayList<>();
            for (JsonNode article : articles) {
                LocalDateTime publishedAt = parseTime(article.path("date").asText());
                if (publishedAt == null || publishedAt.toLocalDate().isBefore(startDate)
                        || publishedAt.toLocalDate().isAfter(endDate)) continue;
                results.add(new NewsArticle(
                        article.path("code").asText(), cleanHtml(article.path("title").asText()),
                        truncate(cleanHtml(article.path("content").asText()), 500),
                        article.path("mediaName").asText(), article.path("url").asText(), publishedAt));
                if (results.size() >= pageSize) break;
            }
            return List.copyOf(results);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("东财新闻接口调用失败", e);
        }
    }

    private java.util.Map<String, Object> buildRequest(String keyword, int pageSize) {
        return java.util.Map.of(
                "uid", "", "keyword", keyword, "type", List.of("cmsArticleWebOld"),
                "client", "web", "clientType", "web", "clientVersion", "curr",
                "param", java.util.Map.of("cmsArticleWebOld", java.util.Map.of(
                        "searchScope", "default", "sort", "default", "pageIndex", 1,
                        "pageSize", pageSize, "preTag", "", "postTag", "")));
    }

    private void throttle() throws InterruptedException {
        synchronized (throttleLock) {
            long wait = minimumIntervalMillis - (System.currentTimeMillis() - lastRequestAt);
            if (wait > 0) Thread.sleep(wait + ThreadLocalRandom.current().nextLong(100, 401));
            lastRequestAt = System.currentTimeMillis();
        }
    }

    static String unwrapJsonp(String value) {
        int start = value == null ? -1 : value.indexOf('(');
        int end = value == null ? -1 : value.lastIndexOf(')');
        if (start < 0 || end <= start) throw new IllegalArgumentException("新闻接口返回的 JSONP 格式不合法");
        return value.substring(start + 1, end);
    }

    private LocalDateTime parseTime(String value) {
        try {
            return LocalDateTime.parse(value, PUBLISHED_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String cleanHtml(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
