package com.stockmind.infrastructure.sector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmind.application.sector.SectorDataProvider;
import com.stockmind.application.sector.SectorConstituent;
import com.stockmind.application.sector.SectorConstituentSet;
import com.stockmind.application.sector.SectorSnapshot;
import com.stockmind.infrastructure.eastmoney.EastmoneyRequestGate;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Eastmoney public API adapter for stock-sector membership and current industry performance. */
@Component
public class EastmoneySectorDataProvider implements SectorDataProvider {
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String membershipUrl;
    private final String stockProfileUrl;
    private final String industryListUrl;
    private final EastmoneyRequestGate requestGate;

    @Autowired
    public EastmoneySectorDataProvider(
            @Value("${stockmind.sector.eastmoney.membership-url:https://push2delay.eastmoney.com/api/qt/slist/get}") String membershipUrl,
            @Value("${stockmind.sector.eastmoney.profile-url:https://push2delay.eastmoney.com/api/qt/stock/get}") String stockProfileUrl,
            @Value("${stockmind.sector.eastmoney.industry-list-url:https://push2delay.eastmoney.com/api/qt/clist/get}") String industryListUrl,
            EastmoneyRequestGate requestGate) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(),
                membershipUrl, stockProfileUrl, industryListUrl, requestGate);
    }

    EastmoneySectorDataProvider(HttpClient httpClient, ObjectMapper objectMapper, String membershipUrl,
                                String industryListUrl, long minimumIntervalMillis) {
        this(httpClient, objectMapper, membershipUrl, "", industryListUrl,
                new EastmoneyRequestGate(minimumIntervalMillis, 0, 60_000, 2_000));
    }

    EastmoneySectorDataProvider(HttpClient httpClient, ObjectMapper objectMapper, String membershipUrl,
                                String industryListUrl, EastmoneyRequestGate requestGate) {
        this(httpClient, objectMapper, membershipUrl, "", industryListUrl, requestGate);
    }

    EastmoneySectorDataProvider(HttpClient httpClient, ObjectMapper objectMapper, String membershipUrl,
                                String stockProfileUrl, String industryListUrl,
                                EastmoneyRequestGate requestGate) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.membershipUrl = membershipUrl;
        this.stockProfileUrl = stockProfileUrl;
        this.industryListUrl = industryListUrl;
        this.requestGate = requestGate;
    }

    @Override
    public List<SectorSnapshot> loadIndustrySectors(String symbol) {
        String code = normalize(symbol);
        try {
            JsonNode membership = get(membershipUrl, "fltt=2&invt=2&secid=" + market(code) + "." + code
                    + "&spt=3&pi=0&pz=200&po=1&fields=f12%2Cf14%2Cf3%2Cf128");
            List<JsonNode> memberships = items(membership.path("data").path("diff"));
            String primaryIndustryName = loadPrimaryIndustryName(code);
            if (!primaryIndustryName.isBlank()) {
                List<SectorSnapshot> exact = memberships.stream()
                        .filter(item -> primaryIndustryName.equals(item.path("f14").asText()))
                        .map(this::sectorSnapshot)
                        .toList();
                if (!exact.isEmpty()) return exact;
            }

            // Fallback for deployments without the stock-profile endpoint. This path keeps
            // backward compatibility but is not the primary classification mechanism because
            // the full industry-list response can intermittently be empty.
            JsonNode industries = get(industryListUrl,
                    "pn=1&pz=200&po=1&np=1&fltt=2&invt=2&fid=f3&fs="
                            + encode("m:90+t:2") + "&fields=f12%2Cf14%2Cf3");
            Set<String> industryCodes = new HashSet<>();
            for (JsonNode item : items(industries.path("data").path("diff"))) {
                industryCodes.add(item.path("f12").asText());
            }
            List<SectorSnapshot> result = new ArrayList<>();
            for (JsonNode item : memberships) {
                String boardCode = item.path("f12").asText();
                if (!industryCodes.contains(boardCode)) continue;
                result.add(sectorSnapshot(item));
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("东财行业板块接口调用失败", e);
        }
    }

    @Override
    public SectorConstituentSet loadTopIndustryConstituents(String symbol, int limit) {
        int requested = Math.max(1, Math.min(10, limit));
        List<SectorSnapshot> sectors = loadIndustrySectors(symbol);
        if (sectors.isEmpty()) throw new IllegalStateException("未找到股票所属行业板块");
        SectorSnapshot sector = sectors.getFirst();
        try {
            JsonNode response = get(industryListUrl,
                    "pn=1&pz=30&po=1&np=1&fltt=2&invt=2&fid=f20&fs="
                            + encode("b:" + sector.code()) + "&fields=f12%2Cf14%2Cf20");
            List<SectorConstituent> constituents = new ArrayList<>();
            for (JsonNode item : items(response.path("data").path("diff"))) {
                String code = item.path("f12").asText();
                if (isAShare(code)) constituents.add(new SectorConstituent(normalizedSymbol(code),
                        item.path("f14").asText(), decimal(item.path("f20"))));
                if (constituents.size() >= requested) break;
            }
            return new SectorConstituentSet(sector.code(), sector.name(), List.copyOf(constituents));
        } catch (Exception e) {
            throw new IllegalStateException("东财行业成分股接口调用失败", e);
        }
    }

    private JsonNode get(String endpoint, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT)
                .header("Referer", "https://quote.eastmoney.com/").GET().build();
        var response = requestGate.get(httpClient, request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("东财行业接口响应状态 " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private String loadPrimaryIndustryName(String code) {
        if (stockProfileUrl == null || stockProfileUrl.isBlank()) return "";
        try {
            JsonNode profile = get(stockProfileUrl,
                    "secid=" + market(code) + "." + code + "&fields=f57%2Cf58%2Cf127");
            return profile.path("data").path("f127").asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private SectorSnapshot sectorSnapshot(JsonNode item) {
        return new SectorSnapshot(item.path("f12").asText(), item.path("f14").asText(),
                decimal(item.path("f3")), item.path("f128").asText());
    }

    private List<JsonNode> items(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node.isArray()) node.forEach(result::add);
        if (node.isObject()) node.elements().forEachRemaining(result::add);
        return result;
    }

    private BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : BigDecimal.ZERO;
    }

    private int market(String code) {
        return code.startsWith("6") || code.startsWith("9") ? 1 : 0;
    }

    private boolean isAShare(String code) {
        return code != null && code.matches("\\d{6}")
                && (code.startsWith("0") || code.startsWith("3") || code.startsWith("4")
                || code.startsWith("6") || code.startsWith("8"))
                && !code.startsWith("200") && !code.startsWith("900");
    }

    private String normalizedSymbol(String code) {
        if (code.startsWith("6") || code.startsWith("9")) return "SH" + code;
        if (code.startsWith("4") || code.startsWith("8")) return "BJ" + code;
        return "SZ" + code;
    }

    private String normalize(String value) {
        String code = value == null ? "" : value.trim().toUpperCase()
                .replaceFirst("^(SH|SZ|BJ)", "").replaceFirst("\\.(SH|SZ|BJ)$", "");
        if (!code.matches("\\d{6}")) throw new IllegalArgumentException("行业查询仅支持6位A股代码");
        return code;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
