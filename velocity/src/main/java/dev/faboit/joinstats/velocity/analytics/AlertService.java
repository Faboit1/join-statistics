package dev.faboit.joinstats.velocity.analytics;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.faboit.joinstats.velocity.config.Messages;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.geo.GeoData;
import dev.faboit.joinstats.velocity.notify.WebhookService;
import dev.faboit.joinstats.velocity.storage.dao.AddressDao;
import dev.faboit.joinstats.velocity.storage.dao.AnnotationDao;
import dev.faboit.joinstats.velocity.storage.dao.SessionDao;
import dev.faboit.joinstats.velocity.storage.model.Records;
import dev.faboit.joinstats.velocity.util.Durations;
import dev.faboit.joinstats.velocity.util.Json;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Watches for the handful of patterns worth telling someone about.
 *
 * <p>Every detector is one query or one arithmetic check on data the plugin is collecting anyway,
 * and every one of them is individually switchable. The design rule throughout is that a detector
 * must not fire repeatedly for the same finding: an alert stream that repeats itself on every join
 * is one nobody reads, which makes the genuinely interesting entry in it invisible.
 */
public final class AlertService {

    /** Alert type identifiers, also used as the webhook event names and message keys. */
    public static final String ALT_DETECTED = "alt-detected";
    public static final String VPN_DETECTED = "vpn-detected";
    public static final String IMPOSSIBLE_TRAVEL = "impossible-travel";
    public static final String RAPID_REJOIN = "rapid-rejoin";
    public static final String FIRST_JOIN = "first-join";
    public static final String NAME_CHANGE = "name-change";
    public static final String LONG_SESSION = "long-session";
    public static final String POPULATION_PEAK = "population-peak";

    /** How long a finding stays suppressed once raised for the same account. */
    private static final long REPEAT_SUPPRESSION_MILLIS = java.util.concurrent.TimeUnit.HOURS
            .toMillis(12);

    private final Supplier<PluginConfig> config;
    private final Supplier<Messages> messages;
    private final ProxyServer proxy;
    private final AnnotationDao annotations;
    private final AddressDao addresses;
    private final SessionDao sessions;
    private final WebhookService webhooks;
    private final Logger logger;

    private final Map<UUID, Deque<Long>> recentLogins = new ConcurrentHashMap<>();
    private final Map<UUID, Long> longSessionNotified = new ConcurrentHashMap<>();

    public AlertService(Supplier<PluginConfig> config, Supplier<Messages> messages,
                        ProxyServer proxy, AnnotationDao annotations, AddressDao addresses,
                        SessionDao sessions, WebhookService webhooks, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.proxy = proxy;
        this.annotations = annotations;
        this.addresses = addresses;
        this.sessions = sessions;
        this.webhooks = webhooks;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ detectors

    /** Flags the very first time an account is seen. */
    public void checkFirstJoin(UUID uuid, String username, GeoData geo) {
        PluginConfig.Alerts settings = config.get().alerts;
        if (!settings.enabled || !settings.firstJoin) {
            return;
        }
        String country = geo == null || geo.country() == null ? "an unknown location"
                : geo.country();
        raise(FIRST_JOIN, WebhookService.Severity.GOOD, uuid, username,
                username + " joined for the first time, from " + country + ".",
                Map.of("Country", country),
                Map.of("player", username, "country", country));
    }

    /** Flags a connection under a username we have not recorded for this account. */
    public void checkNameChange(UUID uuid, String username,
                                List<Records.NameHistoryEntry> history) {
        PluginConfig.Alerts settings = config.get().alerts;
        if (!settings.enabled || !settings.nameChange || history == null || history.size() < 2) {
            return;
        }
        String previous = null;
        for (Records.NameHistoryEntry entry : history) {
            if (!entry.username().equalsIgnoreCase(username)) {
                previous = entry.username();
                break;
            }
        }
        if (previous == null) {
            return;
        }
        raise(NAME_CHANGE, WebhookService.Severity.WARNING, uuid, username,
                username + " was previously known as " + previous + ".",
                Map.of("Previous name", previous, "Known names",
                        String.valueOf(history.size())),
                Map.of("player", username, "previous", previous));
    }

    /**
     * Flags accounts sharing an address with this one.
     *
     * <p>Deliberately reported as "shares an address", not "is an alt": a shared house, a school
     * and a CGNAT range all produce this signal, and presenting a correlation as a conclusion is
     * how staff end up banning siblings.
     */
    public void checkAlts(UUID uuid, String username) {
        PluginConfig.Alerts settings = config.get().alerts;
        if (!settings.enabled || !settings.altAccounts) {
            return;
        }
        long since = System.currentTimeMillis() - settings.altWindow().toMillis();
        addresses.findAlts(uuid, since, settings.altMatchSubnet, 16)
                .thenAccept(alts -> {
                    if (alts.isEmpty()) {
                        return;
                    }
                    suppressed(ALT_DETECTED, uuid).thenAccept(alreadyRaised -> {
                        if (alreadyRaised) {
                            return;
                        }
                        List<String> names = new ArrayList<>(alts.size());
                        for (Records.AltAccount alt : alts) {
                            names.add(alt.exactMatch() ? alt.username()
                                    : alt.username() + " (same subnet)");
                        }
                        String joined = String.join(", ", names);
                        raise(ALT_DETECTED, WebhookService.Severity.WARNING, uuid, username,
                                username + " shares an address with " + joined + ".",
                                Map.of("Accounts", joined, "Matches",
                                        String.valueOf(alts.size())),
                                Map.of("player", username, "others", joined));
                    });
                })
                .exceptionally(error -> {
                    logger.debug("Alt-account check failed for {}.", username, error);
                    return null;
                });
    }

    /** Flags a connection from a VPN, proxy, hosting range or Tor exit. */
    public void checkVpn(UUID uuid, String username, GeoData geo) {
        PluginConfig.Alerts settings = config.get().alerts;
        if (!settings.enabled || !settings.vpnConnections || geo == null || !geo.anonymised()) {
            return;
        }
        String kind = geo.tor() ? "Tor exit node"
                : geo.proxy() ? "VPN or proxy" : "datacentre range";
        String network = geo.asName() != null ? geo.asName()
                : geo.isp() != null ? geo.isp() : "unknown network";
        raise(VPN_DETECTED, WebhookService.Severity.WARNING, uuid, username,
                username + " connected through a " + kind + " (" + network + ").",
                Map.of("Kind", kind, "Network", network,
                        "Country", geo.country() == null ? "unknown" : geo.country()),
                Map.of("player", username, "kind", kind, "network", network));
    }

    /**
     * Flags a location change faster than any aircraft could manage.
     *
     * <p>The check is deliberately conservative — the default of 900 km/h is roughly a jet
     * cruising speed, so a genuine flight does not trip it. Anything above that means two people,
     * or one person and a VPN. Sessions whose address resolved to a proxy are skipped, because a
     * VPN hop is an expected reason to appear elsewhere and is already reported on its own.
     */
    public void checkImpossibleTravel(UUID uuid, String username, GeoData geo, long sessionId,
                                      long now) {
        PluginConfig.Alerts settings = config.get().alerts;
        if (!settings.enabled || !settings.impossibleTravel || geo == null
                || geo.latitude() == null || geo.longitude() == null || geo.anonymised()) {
            return;
        }
        sessions.lastLocation(uuid, sessionId <= 0 ? Long.MAX_VALUE : sessionId)
                .thenAccept(previous -> previous.ifPresent(last -> {
                    long elapsed = now - last.at();
                    if (elapsed <= 0) {
                        return;
                    }
                    double distanceKm = GeoData.haversineKm(last.latitude(), last.longitude(),
                            geo.latitude(), geo.longitude());
                    // Below the accuracy floor of city-level geolocation, the "movement" is noise.
                    if (distanceKm < 100) {
                        return;
                    }
                    double hours = elapsed / 3_600_000.0;
                    double speed = distanceKm / hours;
                    if (speed <= settings.maxTravelKmh) {
                        return;
                    }
                    String from = last.city() != null ? last.city() : last.country();
                    String to = geo.city() != null ? geo.city() : geo.country();
                    raise(IMPOSSIBLE_TRAVEL, WebhookService.Severity.SERIOUS, uuid, username,
                            username + " appeared " + Math.round(distanceKm) + "km away ("
                                    + from + " to " + to + ") after "
                                    + Durations.format(elapsed) + " — " + Math.round(speed)
                                    + " km/h.",
                            Map.of("From", String.valueOf(from), "To", String.valueOf(to),
                                    "Distance", Math.round(distanceKm) + " km",
                                    "Elapsed", Durations.format(elapsed),
                                    "Implied speed", Math.round(speed) + " km/h"),
                            Map.of("player", username, "distance",
                                    String.valueOf(Math.round(distanceKm)),
                                    "elapsed", Durations.format(elapsed),
                                    "speed", String.valueOf(Math.round(speed))));
                }))
                .exceptionally(error -> {
                    logger.debug("Impossible-travel check failed for {}.", username, error);
                    return null;
                });
    }

    /** Flags an account reconnecting repeatedly inside a short window. */
    public void checkRapidRejoin(UUID uuid, String username, long now) {
        PluginConfig.Alerts settings = config.get().alerts;
        if (!settings.enabled || !settings.rapidRejoin) {
            return;
        }
        long window = settings.rejoinWindow().toMillis();
        Deque<Long> history = recentLogins.computeIfAbsent(uuid, key -> new ArrayDeque<>());
        int count;
        synchronized (history) {
            history.addLast(now);
            while (!history.isEmpty() && now - history.peekFirst() > window) {
                history.removeFirst();
            }
            count = history.size();
        }
        if (count < settings.rapidRejoinThreshold) {
            return;
        }
        // Reset so the next alert needs another full run of reconnects, not just one more.
        synchronized (history) {
            history.clear();
        }
        raise(RAPID_REJOIN, WebhookService.Severity.WARNING, uuid, username,
                username + " reconnected " + count + " times in "
                        + Durations.format(window) + ".",
                Map.of("Reconnects", String.valueOf(count),
                        "Window", Durations.format(window)),
                Map.of("player", username, "count", String.valueOf(count),
                        "window", Durations.format(window)));
    }

    /** Flags sessions that have been running longer than the configured limit. */
    public void checkLongSessions(long now) {
        PluginConfig.Alerts settings = config.get().alerts;
        if (!settings.enabled || settings.longSessionAfter().isZero()) {
            return;
        }
        long threshold = settings.longSessionAfter().toMillis();
        long cutoff = now - threshold;
        sessions.openSince(cutoff).thenAccept(open -> {
            for (var session : open) {
                Long notifiedAt = longSessionNotified.get(session.uuid());
                if (notifiedAt != null && notifiedAt >= session.startedAt()) {
                    continue;
                }
                longSessionNotified.put(session.uuid(), now);
                String duration = Durations.format(now - session.startedAt());
                raise(LONG_SESSION, WebhookService.Severity.INFO, session.uuid(),
                        session.username(),
                        session.username() + " has been online for " + duration + ".",
                        Map.of("Duration", duration, "Server",
                                String.valueOf(session.lastServer())),
                        Map.of("player", session.username(), "duration", duration));
            }
        }).exceptionally(error -> {
            logger.debug("Long-session check failed.", error);
            return null;
        });
    }

    /** Flags a new concurrent-player record. */
    public void onNewPeak(int count, int previousPeak, long at) {
        PluginConfig.Alerts settings = config.get().alerts;
        if (!settings.enabled) {
            return;
        }
        // A "record" set while the counter is still climbing from zero on a fresh install is
        // noise; only report once the number is meaningful.
        if (settings.populationSpike > 0 && count < settings.populationSpike) {
            return;
        }
        if (previousPeak <= 0) {
            return;
        }
        raise(POPULATION_PEAK, WebhookService.Severity.GOOD, null, null,
                "New concurrent record: " + count + " players (was " + previousPeak + ").",
                Map.of("Players", String.valueOf(count),
                        "Previous record", String.valueOf(previousPeak)),
                Map.of("count", String.valueOf(count),
                        "previous", String.valueOf(previousPeak)));
    }

    /** Forgets the in-memory state for an account that has just disconnected for good. */
    public void forget(UUID uuid) {
        recentLogins.remove(uuid);
        longSessionNotified.remove(uuid);
    }

    // ------------------------------------------------------------------ plumbing

    /** True when this finding was already raised for this account recently enough to skip. */
    private java.util.concurrent.CompletableFuture<Boolean> suppressed(String type, UUID uuid) {
        long since = System.currentTimeMillis() - REPEAT_SUPPRESSION_MILLIS;
        return annotations.countSince(type, uuid, since)
                .thenApply(count -> count > 0)
                .exceptionally(error -> false);
    }

    /**
     * Stores an alert and fans it out to staff and the webhook.
     *
     * @param fields      structured detail for the webhook embed
     * @param placeholders substitutions for the matching template in {@code messages.conf}
     */
    public void raise(String type, WebhookService.Severity severity, UUID uuid, String username,
                      String message, Map<String, String> fields,
                      Map<String, String> placeholders) {
        long now = System.currentTimeMillis();
        Map<String, String> data = new LinkedHashMap<>(fields == null ? Map.of() : fields);
        annotations.raise(now, type, severity.name().toLowerCase(java.util.Locale.ROOT), uuid,
                username, message, data.isEmpty() ? null : Json.write(data));

        notifyStaff(type, message, placeholders);
        webhooks.submit(type, severity, titleFor(type, username), message, fields);
    }

    private void notifyStaff(String type, String fallback, Map<String, String> placeholders) {
        if (!config.get().alerts.notifyStaff) {
            return;
        }
        Messages text = messages.get();
        String template = text.alertTemplate(type);

        Object[] pairs = new Object[(placeholders.size() + 1) * 2];
        int index = 0;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            pairs[index++] = entry.getKey();
            pairs[index++] = entry.getValue();
        }
        pairs[index++] = "message";
        pairs[index] = fallback;

        net.kyori.adventure.text.Component rendered;
        try {
            rendered = text.render(template, pairs);
        } catch (RuntimeException e) {
            // A broken template in messages.conf must not swallow the alert itself.
            logger.warn("The '{}' alert template could not be rendered; sending plain text.",
                    type, e);
            rendered = net.kyori.adventure.text.Component.text(fallback);
        }

        for (Player player : proxy.getAllPlayers()) {
            if (player.hasPermission("joinstatistics.alerts")) {
                player.sendMessage(rendered);
            }
        }
        proxy.getConsoleCommandSource().sendMessage(rendered);
    }

    private static String titleFor(String type, String username) {
        String label = switch (type) {
            case ALT_DETECTED -> "Shared address";
            case VPN_DETECTED -> "Anonymised connection";
            case IMPOSSIBLE_TRAVEL -> "Impossible travel";
            case RAPID_REJOIN -> "Rapid reconnects";
            case FIRST_JOIN -> "First join";
            case NAME_CHANGE -> "Name change";
            case LONG_SESSION -> "Long session";
            case POPULATION_PEAK -> "New player record";
            default -> type;
        };
        return username == null ? label : label + " — " + username;
    }
}
