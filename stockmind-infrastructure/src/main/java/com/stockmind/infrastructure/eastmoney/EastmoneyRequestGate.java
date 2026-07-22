package com.stockmind.infrastructure.eastmoney;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Shared host-level access control for every Eastmoney HTTP adapter. */
public final class EastmoneyRequestGate {
    private final long minimumIntervalMillis;
    private final long cacheTtlMillis;
    private final long forbiddenCircuitMillis;
    private final long errorBackoffMillis;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final Map<String, HostState> hosts = new ConcurrentHashMap<>();
    private final Map<URI, CachedResponse> cache = new ConcurrentHashMap<>();

    /** Configures per-host throttling, response caching, 403 circuit duration and retry backoff. */
    public EastmoneyRequestGate(
            long minimumIntervalMillis,
            long cacheTtlMillis,
            long forbiddenCircuitMillis,
            long errorBackoffMillis) {
        this(minimumIntervalMillis, cacheTtlMillis, forbiddenCircuitMillis, errorBackoffMillis,
                System::currentTimeMillis, Thread::sleep);
    }

    EastmoneyRequestGate(
            long minimumIntervalMillis,
            long cacheTtlMillis,
            long forbiddenCircuitMillis,
            long errorBackoffMillis,
            LongSupplier clock,
            Sleeper sleeper) {
        this.minimumIntervalMillis = Math.max(0, minimumIntervalMillis);
        this.cacheTtlMillis = Math.max(0, cacheTtlMillis);
        this.forbiddenCircuitMillis = Math.max(0, forbiddenCircuitMillis);
        this.errorBackoffMillis = Math.max(0, errorBackoffMillis);
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Executes a GET through the shared host gate. Successful responses may be served from
     * cache; 403 opens the circuit, while 429 and server errors advance the next allowed time.
     */
    public EastmoneyGatewayResponse get(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        if (!"GET".equalsIgnoreCase(request.method())) {
            throw new IllegalArgumentException("EastmoneyRequestGate仅允许GET请求");
        }
        URI uri = request.uri();
        long now = clock.getAsLong();
        CachedResponse cached = cache.get(uri);
        if (cached != null && cached.expiresAt() >= now) {
            return new EastmoneyGatewayResponse(cached.statusCode(), cached.body(), true);
        }
        if (cached != null) cache.remove(uri, cached);

        String hostKey = uri.getScheme() + "://" + uri.getHost();
        HostState state = hosts.computeIfAbsent(hostKey, ignored -> new HostState());
        synchronized (state) {
            now = clock.getAsLong();
            if (state.forbiddenUntil > now) {
                throw new EastmoneyAccessException("EASTMONEY_CIRCUIT_OPEN",
                        "东方财富访问门已因403熔断: " + hostKey);
            }
            long allowedAt = Math.max(state.nextAllowedAt, state.lastRequestAt + minimumIntervalMillis);
            if (allowedAt > now) sleeper.sleep(allowedAt - now);

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long completedAt = clock.getAsLong();
            state.lastRequestAt = completedAt;
            int status = response.statusCode();
            if (status == 403) {
                state.forbiddenUntil = completedAt + forbiddenCircuitMillis;
            } else if (status == 429 || status >= 500) {
                state.nextAllowedAt = completedAt + errorBackoffMillis;
            } else {
                state.nextAllowedAt = completedAt;
            }
            if (status >= 200 && status < 300 && cacheTtlMillis > 0) {
                cache.put(uri, new CachedResponse(status, response.body(), completedAt + cacheTtlMillis));
            }
            return new EastmoneyGatewayResponse(status, response.body(), false);
        }
    }

    /** Clears response bodies while preserving per-host throttle and circuit state. */
    public void clearCache() {
        cache.clear();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final class HostState {
        private long lastRequestAt = Long.MIN_VALUE / 2;
        private long nextAllowedAt;
        private long forbiddenUntil;
    }

    private record CachedResponse(int statusCode, String body, long expiresAt) {}
}
