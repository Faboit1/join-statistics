package dev.faboit.joinstats.velocity.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.faboit.joinstats.velocity.JoinStatistics;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.storage.dao.PlayerDao;
import dev.faboit.joinstats.velocity.storage.dao.PopulationDao;
import dev.faboit.joinstats.velocity.storage.model.PlayerProfile;
import dev.faboit.joinstats.velocity.storage.model.Records;
import dev.faboit.joinstats.velocity.storage.model.SessionRecord;
import dev.faboit.joinstats.velocity.util.Json;
import dev.faboit.joinstats.velocity.util.Threads;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * A small read-only HTTP interface, for dashboards and monitoring.
 *
 * <p>Deliberately minimal and deliberately read-only: it exposes what the plugin has collected and
 * offers no way to change anything. It speaks plain HTTP, so it binds to loopback by default and
 * refuses to start without a bearer token — the data behind it is every address and session on the
 * network, which is not something to leave open on a public port by accident.
 */
public final class ApiServer {

    private static final int BACKLOG = 16;

    private final PluginConfig.Api settings;
    private final JoinStatistics plugin;
    private final Logger logger;
    private final Map<String, RateLimit> rateLimits = new ConcurrentHashMap<>();

    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(PluginConfig.Api settings, JoinStatistics plugin, Logger logger) {
        this.settings = settings;
        this.plugin = plugin;
        this.logger = logger;
    }

    public void start() throws IOException {
        if (settings.token == null || settings.token.isBlank()) {
            throw new IOException("api.token is empty. Set a long random token, or disable the "
                    + "API — an open endpoint would serve every address and session you have "
                    + "stored to anyone who can reach the port.");
        }
        if (settings.token.length() < 16) {
            logger.warn("api.token is shorter than 16 characters. Use something long and random.");
        }
        if (!"127.0.0.1".equals(settings.bind) && !"localhost".equals(settings.bind)) {
            logger.warn("The HTTP API is bound to {}, not loopback. It speaks plain HTTP, so put "
                    + "a TLS-terminating reverse proxy in front of it.", settings.bind);
        }

        executor = Executors.newFixedThreadPool(2, Threads.factory("api", logger));
        server = HttpServer.create(new InetSocketAddress(settings.bind, settings.port), BACKLOG);
        server.setExecutor(executor);
        server.createContext("/", this::route);
        server.start();
        logger.info("HTTP API listening on http://{}:{}/", settings.bind, settings.port);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        rateLimits.clear();
    }

    // ------------------------------------------------------------------ routing

    private void route(HttpExchange exchange) {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (settings.corsOrigin != null && !settings.corsOrigin.isBlank()) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin",
                        settings.corsOrigin);
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Authorization");
            }
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equals(method)) {
                error(exchange, 405, "Only GET is supported");
                return;
            }
            if (!withinRateLimit(exchange)) {
                error(exchange, 429, "Rate limit exceeded");
                return;
            }

            boolean metrics = path.equals("/metrics");
            if (!(metrics && settings.prometheusUnauthenticated) && !authorised(exchange)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                error(exchange, 401, "A valid bearer token is required");
                return;
            }

            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            if (metrics) {
                if (!settings.prometheus) {
                    error(exchange, 404, "Metrics are disabled");
                    return;
                }
                serveMetrics(exchange);
                return;
            }
            serveJson(exchange, path, query);
        } catch (Exception e) {
            logger.warn("HTTP API request failed.", e);
            try {
                error(exchange, 500, "Internal error");
            } catch (IOException ignored) {
                // The connection is already gone; nothing further to do.
            }
        }
    }

    private void serveJson(HttpExchange exchange, String path, Map<String, String> query)
            throws Exception {
        String[] parts = path.split("/");
        // parts[0] is empty because the path starts with a slash.
        if (parts.length < 3 || !parts[1].equals("api")) {
            error(exchange, 404, "Unknown endpoint. Try /api/overview.");
            return;
        }
        int limit = clamp(query.get("limit"), 50, settings.maxPageSize);
        int offset = Math.max(0, parseInt(query.get("offset"), 0));

        switch (parts[2]) {
            case "health" -> json(exchange, 200, health());
            case "overview" -> json(exchange, 200, awaitOverview());
            case "online" -> json(exchange, 200, online());
            case "servers" -> json(exchange, 200,
                    tallies(plugin.sessionDao().serverTotals(limit).get(5, TimeUnit.SECONDS)));
            case "alerts" -> json(exchange, 200, alerts(query.get("type"), limit, offset));
            case "top" -> json(exchange, 200, top(query.get("metric"), limit, offset));
            case "counts" -> json(exchange, 200, counts(query, limit));
            case "players" -> {
                if (parts.length < 4) {
                    error(exchange, 400, "Expected /api/players/{name-or-uuid}");
                    return;
                }
                String who = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
                String section = parts.length > 4 ? parts[4] : null;
                servePlayer(exchange, who, section, limit, offset);
            }
            default -> error(exchange, 404, "Unknown endpoint");
        }
    }

    private void servePlayer(HttpExchange exchange, String who, String section, int limit,
                             int offset) throws Exception {
        java.util.Optional<PlayerProfile> found =
                plugin.players().resolve(who).get(5, TimeUnit.SECONDS);
        if (found.isEmpty()) {
            error(exchange, 404, "No such account");
            return;
        }
        PlayerProfile profile = found.get();

        if (section == null) {
            Records.FullProfile full =
                    plugin.profiles().full(profile, limit, 60).get(10, TimeUnit.SECONDS);
            json(exchange, 200, fullProfile(full));
            return;
        }
        switch (section) {
            case "sessions" -> {
                List<SessionRecord> rows = plugin.sessionDao()
                        .recent(profile.uuid(), limit, offset).get(5, TimeUnit.SECONDS);
                JsonArray array = new JsonArray();
                rows.forEach(session -> array.add(session(session)));
                json(exchange, 200, wrap("sessions", array));
            }
            case "placeholders" -> {
                var values = plugin.placeholderDao().current(profile.uuid())
                        .get(5, TimeUnit.SECONDS);
                JsonObject body = new JsonObject();
                values.forEach(value -> body.addProperty(value.placeholder(), value.value()));
                json(exchange, 200, wrap("placeholders", body));
            }
            case "addresses" -> {
                var rows = plugin.addresses().geoFor(profile.uuid()).get(5, TimeUnit.SECONDS);
                JsonArray array = new JsonArray();
                for (var address : rows) {
                    JsonObject entry = new JsonObject();
                    // The address itself is deliberately not returned: the dashboards this feeds
                    // want the location, and shipping raw addresses over plain HTTP is a
                    // liability nobody asked for.
                    entry.addProperty("country", address.country());
                    entry.addProperty("countryCode", address.countryCode());
                    entry.addProperty("city", address.city());
                    entry.addProperty("network", address.asName());
                    entry.addProperty("anonymised", address.anonymised());
                    entry.addProperty("firstSeen", address.firstSeen());
                    entry.addProperty("lastSeen", address.lastSeen());
                    array.add(entry);
                }
                json(exchange, 200, wrap("addresses", array));
            }
            case "activity" -> {
                var cells = plugin.sessionDao().hourlyActivity(profile.uuid())
                        .get(5, TimeUnit.SECONDS);
                JsonArray array = new JsonArray();
                for (Records.ActivityCell cell : cells) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("dayOfWeek", cell.dayOfWeek());
                    entry.addProperty("hour", cell.hour());
                    entry.addProperty("playtime", cell.playtime());
                    entry.addProperty("sessions", cell.sessions());
                    array.add(entry);
                }
                json(exchange, 200, wrap("activity", array));
            }
            default -> error(exchange, 404, "Unknown section");
        }
    }

    // ------------------------------------------------------------------ payloads

    private JsonObject health() {
        JsonObject body = new JsonObject();
        body.addProperty("status", "ok");
        body.addProperty("version", dev.faboit.joinstats.velocity.BuildConstants.VERSION);
        body.addProperty("online", plugin.proxy().getPlayerCount());
        body.addProperty("trackedSessions", plugin.sessions().all().size());
        body.addProperty("queuedWrites", plugin.writes().stats().queued());
        return body;
    }

    private JsonObject awaitOverview() throws Exception {
        long todayStart = dev.faboit.joinstats.velocity.util.Ticks.startOfDay(
                System.currentTimeMillis(), plugin.zone());
        Records.Overview overview =
                plugin.maintenance().overview(todayStart).get(10, TimeUnit.SECONDS);
        JsonObject body = new JsonObject();
        body.addProperty("players", overview.players());
        body.addProperty("sessions", overview.sessions());
        body.addProperty("events", overview.events());
        body.addProperty("addresses", overview.addresses());
        body.addProperty("totalPlaytime", overview.totalPlaytime());
        body.addProperty("chatMessages", overview.chatMessages());
        body.addProperty("commands", overview.commands());
        body.addProperty("alerts", overview.alerts());
        body.addProperty("peakOnline", overview.peakOnline());
        body.addProperty("peakOnlineAt", overview.peakOnlineAt());
        body.addProperty("databaseBytes", overview.databaseBytes());
        body.addProperty("newPlayersToday", overview.newPlayersToday());
        body.addProperty("activeToday", overview.activeToday());
        body.addProperty("onlineNow", plugin.proxy().getPlayerCount());
        return body;
    }

    private JsonObject online() {
        JsonArray array = new JsonArray();
        long now = System.currentTimeMillis();
        plugin.proxy().getAllPlayers().forEach(player -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", player.getUniqueId().toString());
            entry.addProperty("username", player.getUsername());
            entry.addProperty("server", player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName()).orElse(null));
            entry.addProperty("ping", player.getPing());
            entry.addProperty("version",
                    dev.faboit.joinstats.velocity.util.Versions.name(player.getProtocolVersion()));
            plugin.sessions().session(player.getUniqueId()).ifPresent(session -> {
                entry.addProperty("sessionMillis", session.connectedMillis(now));
                entry.addProperty("connections", session.connections());
            });
            array.add(entry);
        });
        return wrap("online", array);
    }

    private JsonObject counts(Map<String, String> query, int limit) throws Exception {
        long now = System.currentTimeMillis();
        long to = parseLong(query.get("to"), now);
        long from = parseLong(query.get("from"), now - TimeUnit.HOURS.toMillis(1));
        String bucket = query.get("bucket");

        JsonArray array = new JsonArray();
        if (bucket != null && !bucket.isBlank()) {
            long width = dev.faboit.joinstats.velocity.util.Durations
                    .parse(bucket, java.time.Duration.ofMinutes(1)).toMillis();
            var buckets = plugin.population()
                    .buckets(width, PopulationDao.SCOPE_PROXY, "", from, to, limit)
                    .get(10, TimeUnit.SECONDS);
            for (Records.PopulationBucket entry : buckets) {
                JsonObject point = new JsonObject();
                point.addProperty("at", entry.bucket());
                point.addProperty("average", entry.average());
                point.addProperty("minimum", entry.minimum());
                point.addProperty("maximum", entry.maximum());
                point.addProperty("samples", entry.samples());
                array.add(point);
            }
        } else {
            var samples = plugin.population().samples(from, to, limit).get(10, TimeUnit.SECONDS);
            for (Records.PopulationSample sample : samples) {
                JsonObject point = new JsonObject();
                point.addProperty("at", sample.at());
                point.addProperty("total", sample.total());
                array.add(point);
            }
        }
        JsonObject body = wrap("counts", array);
        body.addProperty("from", from);
        body.addProperty("to", to);
        return body;
    }

    private JsonObject top(String metricName, int limit, int offset) throws Exception {
        PlayerDao.Metric metric = PlayerDao.Metric.of(
                metricName == null ? "playtime" : metricName);
        if (metric == null) {
            metric = PlayerDao.Metric.PLAYTIME;
        }
        var rows = plugin.players().top(metric, limit, offset).get(10, TimeUnit.SECONDS);
        JsonArray array = new JsonArray();
        for (Records.Ranked row : rows) {
            JsonObject entry = new JsonObject();
            entry.addProperty("rank", row.rank());
            entry.addProperty("uuid", row.uuid().toString());
            entry.addProperty("username", row.username());
            entry.addProperty("value", row.value());
            entry.addProperty("display", row.display());
            array.add(entry);
        }
        JsonObject body = wrap("top", array);
        body.addProperty("metric", metric.name().toLowerCase(Locale.ROOT));
        return body;
    }

    private JsonObject alerts(String type, int limit, int offset) throws Exception {
        var rows = plugin.annotations().recentAlerts(type, limit, offset).get(10, TimeUnit.SECONDS);
        JsonArray array = new JsonArray();
        for (Records.Alert alert : rows) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", alert.id());
            entry.addProperty("at", alert.at());
            entry.addProperty("type", alert.type());
            entry.addProperty("severity", alert.severity());
            entry.addProperty("uuid", alert.uuid() == null ? null : alert.uuid().toString());
            entry.addProperty("username", alert.username());
            entry.addProperty("message", alert.message());
            entry.addProperty("acknowledged", alert.acknowledged());
            array.add(entry);
        }
        return wrap("alerts", array);
    }

    private JsonObject fullProfile(Records.FullProfile full) {
        PlayerProfile profile = full.profile();
        JsonObject body = new JsonObject();
        body.addProperty("uuid", profile.uuid().toString());
        body.addProperty("username", profile.username());
        body.addProperty("firstSeen", profile.firstSeen());
        body.addProperty("lastSeen", profile.lastSeen());
        body.addProperty("playtime", profile.playtime());
        body.addProperty("idleTime", profile.idleTime());
        body.addProperty("sessions", profile.sessions());
        body.addProperty("connections", profile.connections());
        body.addProperty("longestSession", profile.longestSession());
        body.addProperty("averageSession", profile.averageSession());
        body.addProperty("chatMessages", profile.chatMessages());
        body.addProperty("commands", profile.commands());
        body.addProperty("kicks", profile.kicks());
        body.addProperty("serverSwitches", profile.serverSwitches());
        body.addProperty("averagePing", profile.averagePing());
        body.addProperty("country", profile.lastCountry());
        body.addProperty("city", profile.lastCity());
        body.addProperty("version", profile.lastVersion());
        body.addProperty("brand", profile.lastBrand());
        body.addProperty("locale", profile.lastLocale());
        body.addProperty("onlineMode", profile.onlineMode());
        body.addProperty("online", plugin.proxy().getPlayer(profile.uuid()).isPresent());

        JsonArray names = new JsonArray();
        full.names().forEach(entry -> names.add(entry.username()));
        body.add("knownNames", names);

        JsonArray servers = new JsonArray();
        for (Records.ServerPlaytime server : full.servers()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("server", server.server());
            entry.addProperty("playtime", server.playtime());
            entry.addProperty("joins", server.joins());
            servers.add(entry);
        }
        body.add("servers", servers);

        JsonObject placeholders = new JsonObject();
        full.placeholders().forEach(value ->
                placeholders.addProperty(value.placeholder(), value.value()));
        body.add("placeholders", placeholders);

        JsonArray tags = new JsonArray();
        full.tags().forEach(tag -> tags.add(tag.tag()));
        body.add("tags", tags);

        JsonArray alts = new JsonArray();
        full.alts().forEach(alt -> alts.add(alt.username()));
        body.add("sharesAddressWith", alts);

        return body;
    }

    private JsonObject session(SessionRecord session) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", session.id());
        entry.addProperty("startedAt", session.startedAt());
        entry.addProperty("endedAt", session.endedAt());
        entry.addProperty("duration", session.duration());
        entry.addProperty("gapTime", session.gapTime());
        entry.addProperty("connections", session.connections());
        entry.addProperty("open", session.open());
        entry.addProperty("firstServer", session.firstServer());
        entry.addProperty("lastServer", session.lastServer());
        entry.addProperty("serversSeen", session.serversSeen());
        entry.addProperty("chatMessages", session.chatMessages());
        entry.addProperty("commands", session.commands());
        entry.addProperty("version", session.versionName());
        entry.addProperty("brand", session.brand());
        entry.addProperty("virtualHost", session.virtualHost());
        entry.addProperty("countryCode", session.countryCode());
        entry.addProperty("averagePing", session.averagePing());
        entry.addProperty("quitReason", session.quitReason());
        return entry;
    }

    private static JsonObject tallies(List<Records.Tally> rows) {
        JsonArray array = new JsonArray();
        for (Records.Tally row : rows) {
            JsonObject entry = new JsonObject();
            entry.addProperty("key", row.key());
            entry.addProperty("value", row.count());
            entry.addProperty("display", row.display());
            array.add(entry);
        }
        return wrap("items", array);
    }

    // ------------------------------------------------------------------ metrics

    /** Prometheus text exposition, for the handful of gauges worth scraping. */
    private void serveMetrics(HttpExchange exchange) throws Exception {
        StringBuilder body = new StringBuilder(2048);
        var writes = plugin.writes().stats();
        var geo = plugin.geo().stats();
        var bridge = plugin.bridge().stats();

        gauge(body, "joinstats_players_online", "Players connected to the proxy",
                plugin.proxy().getPlayerCount());
        gauge(body, "joinstats_sessions_tracked", "Sessions held in memory, including lingering",
                plugin.sessions().all().size());
        gauge(body, "joinstats_sessions_live", "Sessions currently connected",
                plugin.sessions().onlineCount());
        gauge(body, "joinstats_population_peak", "Highest concurrent player count on record",
                plugin.sampler().peak());
        gauge(body, "joinstats_write_queue_depth", "Statements waiting to be written",
                writes.queued());
        counter(body, "joinstats_writes_applied_total", "Statements written", writes.applied());
        counter(body, "joinstats_write_commits_total", "Transactions committed", writes.commits());
        counter(body, "joinstats_writes_dropped_total",
                "Statements discarded because the queue was full", writes.dropped());
        counter(body, "joinstats_write_failures_total", "Batches that failed and rolled back",
                writes.failures());
        counter(body, "joinstats_geo_hits_total", "Geolocation lookups served from cache",
                geo.hits());
        counter(body, "joinstats_geo_lookups_total", "Geolocation lookups sent to a provider",
                geo.misses());
        counter(body, "joinstats_geo_failures_total", "Geolocation lookups that failed",
                geo.failures());
        gauge(body, "joinstats_bridge_backends", "Backends running the companion plugin",
                bridge.backends());
        counter(body, "joinstats_bridge_answered_total", "Bridge requests answered",
                bridge.answered());
        counter(body, "joinstats_bridge_timeouts_total", "Bridge requests that timed out",
                bridge.timedOut());
        counter(body, "joinstats_population_samples_total", "Population samples taken",
                plugin.sampler().samplesTaken());

        // Per-server player counts, which is what most dashboards actually graph.
        body.append("# HELP joinstats_server_players Players connected to each backend\n");
        body.append("# TYPE joinstats_server_players gauge\n");
        plugin.proxy().getAllServers().forEach(server -> body
                .append("joinstats_server_players{server=\"")
                .append(escapeLabel(server.getServerInfo().getName()))
                .append("\"} ")
                .append(server.getPlayersConnected().size())
                .append('\n'));

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static void gauge(StringBuilder body, String name, String help, long value) {
        metric(body, name, help, "gauge", value);
    }

    private static void counter(StringBuilder body, String name, String help, long value) {
        metric(body, name, help, "counter", value);
    }

    private static void metric(StringBuilder body, String name, String help, String type,
                               long value) {
        body.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(' ').append(type).append('\n')
                .append(name).append(' ').append(value).append('\n');
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Compares the bearer token in constant time.
     *
     * <p>A naive {@code equals} leaks the token one character at a time to anyone willing to
     * measure how long the comparison took.
     */
    private boolean authorised(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return false;
        }
        String presented = header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : header.trim();
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                settings.token.getBytes(StandardCharsets.UTF_8));
    }

    private boolean withinRateLimit(HttpExchange exchange) {
        if (settings.rateLimitPerMinute <= 0) {
            return true;
        }
        String key = exchange.getRemoteAddress() == null ? "unknown"
                : exchange.getRemoteAddress().getAddress().getHostAddress();
        long minute = System.currentTimeMillis() / 60_000L;
        RateLimit limit = rateLimits.computeIfAbsent(key, ignored -> new RateLimit(minute));
        synchronized (limit) {
            if (limit.minute != minute) {
                limit.minute = minute;
                limit.count.set(0);
            }
            return limit.count.incrementAndGet() <= settings.rateLimitPerMinute;
        }
    }

    private static JsonObject wrap(String key, com.google.gson.JsonElement value) {
        JsonObject body = new JsonObject();
        body.add(key, value);
        return body;
    }

    private void json(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        json(exchange, status, body);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                values.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static int clamp(String raw, int fallback, int maximum) {
        return Math.max(1, Math.min(Math.max(1, maximum), parseInt(raw, fallback)));
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return raw == null ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class RateLimit {
        private long minute;
        private final AtomicInteger count = new AtomicInteger();

        RateLimit(long minute) {
            this.minute = minute;
        }
    }
}
