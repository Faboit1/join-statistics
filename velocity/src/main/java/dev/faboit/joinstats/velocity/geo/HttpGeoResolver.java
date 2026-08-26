package dev.faboit.joinstats.velocity.geo;

import com.google.gson.JsonObject;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.util.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Lookups against a REST geolocation service.
 *
 * <p>Used as the fallback when no local database is installed. It carries two costs a local file
 * does not — a rate limit, and disclosing player addresses to a third party — so the token bucket
 * below refuses rather than queues once the budget is spent, and the plugin ships with this
 * provider second in the chain.
 */
public final class HttpGeoResolver implements GeoResolver {

    private final PluginConfig.HttpLookup settings;
    private final Logger logger;
    private final HttpClient client;
    private final TokenBucket budget;

    public HttpGeoResolver(PluginConfig.HttpLookup settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.budget = new TokenBucket(Math.max(1, settings.rateLimitPerMinute));
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public boolean available() {
        return settings.endpoint != null && settings.endpoint.contains("{ip}");
    }

    @Override
    public GeoData resolve(String address) throws Exception {
        if (!available()) {
            return GeoData.empty(name());
        }
        if (!budget.tryConsume()) {
            throw new RateLimitedException("The geolocation endpoint's rate limit is exhausted");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(settings.endpoint.replace("{ip}",
                        java.net.URLEncoder.encode(address, java.nio.charset.StandardCharsets.UTF_8))))
                .timeout(settings.requestTimeout())
                .header("Accept", "application/json")
                .header("User-Agent", "JoinStatistics")
                .GET();
        if (!settings.authorization.isBlank()) {
            request.header("Authorization", settings.authorization);
        }

        HttpResponse<String> response =
                client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            // Back the bucket off to the service's own opinion of the limit.
            budget.penalise();
            throw new RateLimitedException("The geolocation endpoint returned 429");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "The geolocation endpoint returned HTTP " + response.statusCode());
        }

        JsonObject body = Json.tree(response.body()).getAsJsonObject();
        String status = string(body, "status");
        if (status != null && !status.equalsIgnoreCase("success")) {
            // "private range" and "reserved range" are expected for LAN addresses.
            logger.debug("Geolocation for {} returned {}: {}", address, status,
                    string(body, "message"));
            return GeoData.empty(name());
        }

        return new GeoData(
                string(body, "reverse"),
                string(body, "continent"),
                string(body, "country"),
                string(body, "countryCode"),
                first(string(body, "regionName"), string(body, "region")),
                string(body, "city"),
                string(body, "zip"),
                number(body, "lat"),
                number(body, "lon"),
                null,
                string(body, "timezone"),
                string(body, "isp"),
                string(body, "org"),
                parseAsn(string(body, "as")),
                first(string(body, "asname"), string(body, "as")),
                bool(body, "mobile"),
                bool(body, "proxy"),
                bool(body, "hosting"),
                false,
                name());
    }

    /** ip-api returns the AS as {@code "AS15169 Google LLC"}; take the number. */
    private static Integer parseAsn(String raw) {
        if (raw == null || !raw.startsWith("AS")) {
            return null;
        }
        int end = 2;
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            end++;
        }
        if (end == 2) {
            return null;
        }
        try {
            return Integer.parseInt(raw.substring(2, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String string(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) {
            return null;
        }
        String value = body.get(key).getAsString();
        return value.isBlank() ? null : value;
    }

    private static Double number(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) {
            return null;
        }
        try {
            return body.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean bool(JsonObject body, String key) {
        try {
            return body.has(key) && !body.get(key).isJsonNull() && body.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String first(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    /** Signals that a lookup should be retried later rather than treated as "no data". */
    public static final class RateLimitedException extends Exception {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    /**
     * A minute-granularity token bucket.
     *
     * <p>Refills to the full allowance at each minute boundary rather than continuously, which
     * matches how the free tiers of these services actually meter.
     */
    private static final class TokenBucket {
        private final int perMinute;
        private final AtomicLong state = new AtomicLong();

        TokenBucket(int perMinute) {
            this.perMinute = perMinute;
        }

        boolean tryConsume() {
            long minute = System.currentTimeMillis() / 60_000L;
            while (true) {
                long current = state.get();
                long currentMinute = current >>> 20;
                long used = current & 0xFFFFF;
                if (currentMinute != minute) {
                    if (state.compareAndSet(current, (minute << 20) | 1L)) {
                        return true;
                    }
                    continue;
                }
                if (used >= perMinute) {
                    return false;
                }
                if (state.compareAndSet(current, (minute << 20) | (used + 1))) {
                    return true;
                }
            }
        }

        /** Burns the rest of this minute's allowance after the service pushed back. */
        void penalise() {
            long minute = System.currentTimeMillis() / 60_000L;
            state.set((minute << 20) | perMinute);
        }
    }
}
