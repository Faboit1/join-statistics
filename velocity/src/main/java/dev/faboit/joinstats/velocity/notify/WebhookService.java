package dev.faboit.joinstats.velocity.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.util.Json;
import dev.faboit.joinstats.velocity.util.Threads;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Posts notable events to an HTTP endpoint — a Discord channel webhook, or anything that accepts
 * JSON.
 *
 * <p>Events are buffered and flushed on a timer rather than sent one request per event. A rush of
 * joins after a restart would otherwise burn a Discord webhook's rate limit in seconds and get
 * the rest of the evening's notifications dropped by the far end.
 */
public final class WebhookService implements AutoCloseable {

    private static final int DISCORD_EMBED_LIMIT = 10;

    private final Supplier<PluginConfig> config;
    private final Logger logger;
    private final HttpClient client;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<Payload> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong minuteState = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean warnedAboutUrl;

    public WebhookService(Supplier<PluginConfig> config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.executor = Executors.newSingleThreadExecutor(Threads.factory("webhook", logger));
    }

    public boolean enabled() {
        PluginConfig.Webhooks settings = config.get().webhooks;
        if (!settings.enabled) {
            return false;
        }
        if (settings.url == null || settings.url.isBlank()) {
            if (!warnedAboutUrl) {
                warnedAboutUrl = true;
                logger.warn("Webhooks are enabled but no URL is configured; nothing will be sent.");
            }
            return false;
        }
        return true;
    }

    /**
     * Queues an event for delivery.
     *
     * <p>Returns immediately and never throws — a notification failing is not a reason for the
     * join that triggered it to be handled any differently.
     */
    public void submit(String event, Severity severity, String title, String description,
                       Map<String, String> fields) {
        if (!enabled() || !config.get().webhooks.sends(event)) {
            return;
        }
        pending.add(new Payload(event, severity, title, description,
                fields == null ? Map.of() : new LinkedHashMap<>(fields),
                System.currentTimeMillis()));
    }

    /** Sends everything queued since the last call. Driven by the scheduler. */
    public void flush() {
        if (pending.isEmpty() || !enabled()) {
            return;
        }
        List<Payload> batch = new ArrayList<>();
        Payload item;
        while ((item = pending.poll()) != null) {
            batch.add(item);
            if (batch.size() >= DISCORD_EMBED_LIMIT) {
                dispatch(new ArrayList<>(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            dispatch(batch);
        }
    }

    private void dispatch(List<Payload> batch) {
        PluginConfig.Webhooks settings = config.get().webhooks;
        if (!consumeRateLimit(settings.rateLimitPerMinute)) {
            dropped.addAndGet(batch.size());
            return;
        }
        String body = "discord".equalsIgnoreCase(settings.format)
                ? discordBody(batch) : rawBody(batch);
        String url = settings.url;
        Duration timeout = settings.requestTimeout();

        executor.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "JoinStatistics")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    delivered.addAndGet(batch.size());
                } else {
                    dropped.addAndGet(batch.size());
                    logger.warn("Webhook delivery returned HTTP {}: {}", response.statusCode(),
                            abbreviate(response.body()));
                }
            } catch (Exception e) {
                dropped.addAndGet(batch.size());
                logger.warn("Webhook delivery failed: {}", e.getMessage());
            }
        });
    }

    /** A Discord webhook payload: one embed per event, at most ten per request. */
    private String discordBody(List<Payload> batch) {
        JsonArray embeds = new JsonArray();
        for (Payload payload : batch) {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", payload.title);
            if (payload.description != null && !payload.description.isBlank()) {
                embed.addProperty("description", payload.description);
            }
            embed.addProperty("color", payload.severity.colour);
            embed.addProperty("timestamp", Instant.ofEpochMilli(payload.at).toString());

            JsonObject footer = new JsonObject();
            footer.addProperty("text", "JoinStatistics · " + payload.event);
            embed.add("footer", footer);

            if (!payload.fields.isEmpty()) {
                JsonArray fields = new JsonArray();
                for (Map.Entry<String, String> entry : payload.fields.entrySet()) {
                    JsonObject field = new JsonObject();
                    field.addProperty("name", entry.getKey());
                    // Discord rejects an embed field with an empty value outright.
                    field.addProperty("value", entry.getValue() == null || entry.getValue().isBlank()
                            ? "—" : abbreviate(entry.getValue()));
                    field.addProperty("inline", entry.getValue() != null
                            && entry.getValue().length() <= 24);
                    fields.add(field);
                }
                embed.add("fields", fields);
            }
            embeds.add(embed);
        }

        JsonObject body = new JsonObject();
        body.add("embeds", embeds);
        // Nothing this plugin reports is ever worth pinging a whole server for.
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        body.add("allowed_mentions", allowed);
        return Json.write(body);
    }

    /** A plain JSON payload for a consumer of your own. */
    private String rawBody(List<Payload> batch) {
        JsonArray events = new JsonArray();
        for (Payload payload : batch) {
            JsonObject entry = new JsonObject();
            entry.addProperty("event", payload.event);
            entry.addProperty("severity", payload.severity.name().toLowerCase(java.util.Locale.ROOT));
            entry.addProperty("title", payload.title);
            entry.addProperty("description", payload.description);
            entry.addProperty("at", payload.at);
            JsonObject fields = new JsonObject();
            payload.fields.forEach(fields::addProperty);
            entry.add("fields", fields);
            events.add(entry);
        }
        JsonObject body = new JsonObject();
        body.addProperty("source", "joinstatistics");
        body.add("events", events);
        return Json.write(body);
    }

    /** Minute-granularity budget, matching how webhook providers actually meter. */
    private boolean consumeRateLimit(int perMinute) {
        if (perMinute <= 0) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        while (true) {
            long current = minuteState.get();
            long currentMinute = current >>> 20;
            long used = current & 0xFFFFF;
            if (currentMinute != minute) {
                if (minuteState.compareAndSet(current, (minute << 20) | 1L)) {
                    return true;
                }
                continue;
            }
            if (used >= perMinute) {
                return false;
            }
            if (minuteState.compareAndSet(current, (minute << 20) | (used + 1))) {
                return true;
            }
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 1000 ? value : value.substring(0, 997) + "...";
    }

    public Stats stats() {
        return new Stats(enabled(), pending.size(), delivered.get(), dropped.get());
    }

    @Override
    public void close() {
        flush();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** How prominently an event should be rendered. */
    public enum Severity {
        INFO(0x7AA2F7),
        GOOD(0x9ECE6A),
        WARNING(0xE0AF68),
        SERIOUS(0xF7768E);

        private final int colour;

        Severity(int colour) {
            this.colour = colour;
        }
    }

    private record Payload(String event, Severity severity, String title, String description,
                           Map<String, String> fields, long at) {
    }

    /** Delivery counters, for {@code /joinstats status}. */
    public record Stats(boolean enabled, int queued, long delivered, long dropped) {
    }
}
