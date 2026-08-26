package dev.faboit.joinstats.velocity.bridge;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.storage.dao.PlaceholderDao;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Decides which placeholders to ask for, when, and what to keep.
 *
 * <p>Split from {@link BridgeService} on purpose: that class knows how to move bytes, this one
 * knows the policy — the global list plus any per-server additions, the refresh cadence, and
 * whether a value that has not changed is worth another history row.
 */
public final class PlaceholderService {

    private final ProxyServer proxy;
    private final BridgeService bridge;
    private final PlaceholderDao storage;
    private final Supplier<PluginConfig> config;
    private final Logger logger;

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong stored = new AtomicLong();

    public PlaceholderService(ProxyServer proxy, BridgeService bridge, PlaceholderDao storage,
                              Supplier<PluginConfig> config, Logger logger) {
        this.proxy = proxy;
        this.bridge = bridge;
        this.storage = storage;
        this.config = config;
        this.logger = logger;
    }

    public boolean enabled() {
        return config.get().placeholders.enabled;
    }

    /**
     * The placeholders to request for a player on a given server.
     *
     * <p>The global list plus that server's extras, de-duplicated. A {@link LinkedHashSet} keeps
     * the configured order, which makes the stored set stable and command output predictable.
     */
    public List<String> placeholdersFor(String server) {
        PluginConfig.Placeholders settings = config.get().placeholders;
        Set<String> combined = new LinkedHashSet<>(settings.track);
        if (server != null) {
            List<String> extras = settings.perServer.get(server);
            if (extras != null) {
                combined.addAll(extras);
            }
        }
        combined.removeIf(value -> value == null || value.isBlank());
        return new ArrayList<>(combined);
    }

    /** Requests and stores every tracked placeholder for one player. */
    public CompletableFuture<Integer> refresh(Player player) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(0);
        }
        ServerConnection connection = player.getCurrentServer().orElse(null);
        if (connection == null) {
            return CompletableFuture.completedFuture(0);
        }
        String server = connection.getServerInfo().getName();
        List<String> wanted = placeholdersFor(server);
        if (wanted.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        PluginConfig.Placeholders settings = config.get().placeholders;
        long timeout = settings.timeout().toMillis();
        int batchSize = Math.max(1, settings.batchSize);

        List<CompletableFuture<Map<String, String>>> batches = new ArrayList<>();
        for (int start = 0; start < wanted.size(); start += batchSize) {
            List<String> batch = wanted.subList(start, Math.min(wanted.size(), start + batchSize));
            requested.addAndGet(batch.size());
            batches.add(bridge.requestPlaceholders(player, batch, timeout));
        }

        UUID uuid = player.getUniqueId();
        return CompletableFuture.allOf(batches.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<String, String> merged = new java.util.LinkedHashMap<>();
                    for (CompletableFuture<Map<String, String>> batch : batches) {
                        merged.putAll(batch.getNow(Map.of()));
                    }
                    return merged.isEmpty() ? 0 : store(uuid, server, merged);
                })
                .exceptionally(error -> {
                    logger.debug("Placeholder refresh failed for {}.", player.getUsername(), error);
                    return 0;
                });
    }

    /**
     * Writes resolved values, appending history only where something actually changed.
     *
     * <p>Most tracked placeholders — a rank, a primary group, a world name — hold the same value
     * for weeks. Writing a history row for each of them on every refresh would grow the largest
     * table in the database out of values that carry no information.
     */
    private int store(UUID uuid, String server, Map<String, String> values) {
        PluginConfig.Placeholders settings = config.get().placeholders;
        long now = System.currentTimeMillis();

        Map<String, String> previous = settings.keepHistory && settings.historyOnChangeOnly
                ? safePrevious(uuid) : Map.of();

        int written = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String placeholder = entry.getKey();
            String value = entry.getValue();
            storage.store(uuid, placeholder, value, server, now);
            written++;

            if (settings.keepHistory
                    && (!settings.historyOnChangeOnly
                        || !Objects.equals(previous.get(placeholder), value))) {
                storage.appendHistory(uuid, placeholder, value, server, now);
            }
        }
        stored.addAndGet(written);
        return written;
    }

    private Map<String, String> safePrevious(UUID uuid) {
        try {
            return storage.currentNow(uuid);
        } catch (RuntimeException e) {
            // Worst case we write a redundant history row; not a reason to lose the update.
            logger.debug("Could not read previous placeholder values for {}.", uuid, e);
            return Map.of();
        }
    }

    /** Refreshes every online player. Driven by the scheduler. */
    public void refreshAll() {
        if (!enabled()) {
            return;
        }
        for (Player player : proxy.getAllPlayers()) {
            refresh(player);
        }
    }

    /** One last capture before a session is written out, if the operator asked for it. */
    public void captureOnQuit(Player player) {
        if (enabled() && config.get().placeholders.captureOnQuit) {
            refresh(player);
        }
    }

    public Stats stats() {
        return new Stats(enabled(), requested.get(), stored.get());
    }

    /** Placeholder counters, for {@code /joinstats status}. */
    public record Stats(boolean enabled, long requested, long stored) {
    }
}
