package dev.faboit.joinstats.velocity.analytics;

import dev.faboit.joinstats.velocity.config.PluginConfig;
import dev.faboit.joinstats.velocity.storage.dao.MaintenanceDao;
import dev.faboit.joinstats.velocity.storage.dao.PlayerDao;
import dev.faboit.joinstats.velocity.util.Durations;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Deletes what the configuration says should no longer be kept.
 *
 * <p>Each table has its own limit, because they age very differently: a per-second population
 * sample is worthless after a week but its rollup is worth keeping forever, and chat is the one
 * table most operators want gone soonest. A limit of {@code "0"} means "keep indefinitely".
 */
public final class RetentionService {

    private final MaintenanceDao maintenance;
    private final PlayerDao players;
    private final Supplier<PluginConfig> config;
    private final Logger logger;

    public RetentionService(MaintenanceDao maintenance, PlayerDao players,
                            Supplier<PluginConfig> config, Logger logger) {
        this.maintenance = maintenance;
        this.players = players;
        this.config = config;
        this.logger = logger;
    }

    /** Runs one pruning pass. Driven by the scheduler, and by {@code /joinstats prune}. */
    public CompletableFuture<Map<String, Integer>> run() {
        PluginConfig.Retention settings = config.get().retention;
        if (!settings.enabled) {
            return CompletableFuture.completedFuture(Map.of());
        }

        long now = System.currentTimeMillis();
        Map<String, CompletableFuture<Integer>> jobs = new LinkedHashMap<>();

        prune(jobs, "events", settings.events, now);
        prune(jobs, "chat", settings.chat, now);
        prune(jobs, "commands", settings.commands, now);
        prune(jobs, "pings", settings.pings, now);
        prune(jobs, "sessions", settings.sessions, now);
        prune(jobs, "placeholder-history", settings.placeholderHistory, now);
        prune(jobs, "alerts", settings.alerts, now);
        prune(jobs, "population-samples", settings.populationSamples, now);
        prune(jobs, "population-breakdown", settings.populationSamples, now);

        Duration rollups = Durations.parse(settings.populationRollups, Duration.ZERO);
        if (!rollups.isZero()) {
            jobs.put("population-rollup", maintenance.pruneRollups(now - rollups.toMillis()));
        }

        // Only worth running once sessions have actually been deleted above.
        jobs.put("orphaned-visits", maintenance.pruneOrphanedVisits());

        Duration inactive = Durations.parse(settings.inactiveProfiles, Duration.ZERO);
        CompletableFuture<Integer> profiles = inactive.isZero()
                ? CompletableFuture.completedFuture(0)
                : forgetInactive(now - inactive.toMillis());
        jobs.put("inactive-profiles", profiles);

        return CompletableFuture.allOf(jobs.values().toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<String, Integer> deleted = new LinkedHashMap<>();
                    long total = 0;
                    for (Map.Entry<String, CompletableFuture<Integer>> entry : jobs.entrySet()) {
                        int rows = entry.getValue().getNow(0);
                        if (rows > 0) {
                            deleted.put(entry.getKey(), rows);
                            total += rows;
                        }
                    }
                    if (total > 0) {
                        logger.info("Retention removed {} rows: {}", total, deleted);
                    }
                    return deleted;
                })
                .exceptionally(error -> {
                    logger.warn("Retention pass failed part-way through.", error);
                    return Map.of();
                });
    }

    private void prune(Map<String, CompletableFuture<Integer>> jobs, String what, String limit,
                       long now) {
        Duration age = Durations.parse(limit, Duration.ZERO);
        if (age.isZero() || age.isNegative()) {
            return;
        }
        jobs.put(what, maintenance.prune(what, now - age.toMillis()));
    }

    /**
     * Erases accounts that have not connected for the configured period.
     *
     * <p>Bounded to a few hundred per pass. A first run after enabling this on a years-old
     * database would otherwise try to delete tens of thousands of profiles in one transaction and
     * hold the write lock for the duration; the remainder is picked up on the next pass.
     */
    private CompletableFuture<Integer> forgetInactive(long cutoff) {
        return players.inactiveSince(cutoff, 250).thenCompose(stale -> {
            if (stale.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            logger.info("Erasing {} profiles that have not connected since {}.",
                    stale.size(), java.time.Instant.ofEpochMilli(cutoff));
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            for (var profile : stale) {
                chain = chain.thenCompose(count -> maintenance.forget(profile.uuid())
                        .thenApply(rows -> count + rows));
            }
            return chain;
        });
    }
}
